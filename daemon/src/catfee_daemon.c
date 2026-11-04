/*
 * catfee_daemon.c —— CatfeeRootAuto 底层能力守护进程
 * =====================================================
 * 功能描述:
 *   以 root 身份常驻运行, 通过 uinput 虚拟触屏注入真实内核输入事件,
 *   并提供截图 / shell / 屏幕信息等原子能力。
 *   对外暴露两种调用方式:
 *     1) Unix Socket 常驻服务 (低延迟, 推荐)
 *     2) 命令行单次调用     (兜底, 每次会重建 uinput 设备)
 *
 * 请求协议 (| 分隔, 便于参数含空格):
 *     <id>|<method>|<arg1>|<arg2>|...
 *   例: 1|tap|540|1200
 *       2|swipe|540|1800|540|600|300
 *       3|shot|/data/local/tmp/catfee_shot.raw
 *       4|shell|ls -l /data/local/tmp
 *
 * 响应协议 (单行 JSON):
 *    {"id":1,"ok":true,"data":{...}}
 *    {"id":1,"ok":false,"err":"原因"}
 *
 * 编译:
 *   clang --target=aarch64-linux-android26 -O2 -o daemon catfee_daemon.c
 *
 * 参数说明:
 *   无外部配置文件; 屏幕尺寸自动探测 (wm size), 失败时回退 1080x2400
 *
 * 返回值:
 *   进程退出码 0=正常 / 1=参数错误 / 2=初始化失败
 *
 * 使用场景:
 *   Magisk 模块开机自启 (service.sh), 由 App 通过 libsu 或 socket 调用
 *
 * 修改时间: 2026-11-03
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define DEFAULT_SOCK_PATH "/data/local/tmp/catfee_daemon.sock"
#define DEFAULT_SHOT_PATH "/data/local/tmp/catfee_shot.raw"
#define MAX_LINE 8192

/* 全局虚拟触屏句柄与屏幕参数, 进程生命周期内复用, 避免重复热插拔开销 */
static int g_uinput_fd = -1;
static int g_screen_w = 1080;
static int g_screen_h = 2400;
static int g_tracking_id = 100;
static pthread_mutex_t g_input_lock = PTHREAD_MUTEX_INITIALIZER;

/* ========================= 基础工具 ========================= */

/**
 * 功能: 毫秒级睡眠
 * 参数: ms 毫秒数
 * 返回: 无
 * 使用场景: 注入事件帧之间的间隔控制
 */
static void sleep_ms(int ms) {
    if (ms <= 0) return;
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

/**
 * 功能: 写日志到 stderr (Magisk 会收集到模块日志文件)
 * 参数: fmt 格式化串, 可变参数
 * 返回: 无
 */
static void logf_msg(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

/**
 * 功能: 执行 shell 命令并读取全部 stdout
 * 参数: cmd 命令字符串; out_len 回填输出长度(可为 NULL)
 * 返回: 堆内存字符串(调用方 free), 失败返回 NULL
 * 使用场景: 探测屏幕尺寸、截图、透传 shell 能力
 */
static char *run_cmd(const char *cmd, size_t *out_len) {
    if (!cmd) return NULL;
    FILE *fp = popen(cmd, "r");
    if (!fp) {
        logf_msg("popen 失败: %s", strerror(errno));
        return NULL;
    }
    size_t cap = 4096, len = 0;
    char *buf = (char *)malloc(cap);
    if (!buf) {
        pclose(fp);
        return NULL;
    }
    while (1) {
        if (len + 1024 >= cap) {
            size_t ncap = cap * 2;
            char *nb = (char *)realloc(buf, ncap);
            if (!nb) break;
            buf = nb;
            cap = ncap;
        }
        size_t n = fread(buf + len, 1, cap - len - 1, fp);
        if (n == 0) break;
        len += n;
    }
    buf[len] = '\0';
    pclose(fp);
    if (out_len) *out_len = len;
    return buf;
}

/* ========================= 虚拟触屏 (uinput) ========================= */

/**
 * 功能: 初始化 uinput 虚拟触屏设备
 * 参数: 无 (使用全局 g_screen_w / g_screen_h)
 * 返回: 0=成功, 负值=失败
 * 关键点:
 *   1. 必须设置 INPUT_PROP_DIRECT, 否则被识别为触摸板, 坐标变相对位移
 *   2. 必须设置 ABS_MT_POSITION_X/Y 的 absmax, 否则默认 0~255 全挤在左上角
 *   3. 采用 slot(type B) 协议, 不要与 type A 混用
 * 使用场景: 进程启动时创建一次, 全程复用
 */
static int uinput_init(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        logf_msg("打开 /dev/uinput 失败: %s", strerror(errno));
        return -1;
    }

    /* 事件类型 */
    if (ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) {
        logf_msg("设置 EVBIT 失败: %s", strerror(errno));
        close(fd);
        return -2;
    }

    /* 按键: 触摸主键 + 常规物理键 */
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, KEY_BACK);
    ioctl(fd, UI_SET_KEYBIT, KEY_HOME);
    /* NDK 内核头未定义 KEY_APP_SWITCH, 直接用 Linux 标准码 580 */
    ioctl(fd, UI_SET_KEYBIT, 580);
    ioctl(fd, UI_SET_KEYBIT, KEY_POWER);
    ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEUP);
    ioctl(fd, UI_SET_KEYBIT, KEY_VOLUMEDOWN);
    ioctl(fd, UI_SET_KEYBIT, KEY_ENTER);

    /* 多点触控轴 */
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(fd, UI_SET_ABSBIT, ABS_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_Y);

    /* 关键: 直接输入设备属性; 设置失败会被识别为触摸板, 坐标全部错乱, 必须视为致命错误 */
    if (ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) {
        logf_msg("设置 INPUT_PROP_DIRECT 失败, 设备会被识别为触摸板: %s", strerror(errno));
        close(fd);
        return -5;
    }

    /* 轴范围必须显式声明 */
    struct uinput_abs_setup abs_setup;
    memset(&abs_setup, 0, sizeof(abs_setup));

    abs_setup.code = ABS_MT_POSITION_X;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = g_screen_w - 1;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_POSITION_Y;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = g_screen_h - 1;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_X;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = g_screen_w - 1;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_Y;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = g_screen_h - 1;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_SLOT;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = 9;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_TRACKING_ID;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = 65535;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_PRESSURE;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = 255;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    abs_setup.code = ABS_MT_TOUCH_MAJOR;
    abs_setup.absinfo.minimum = 0;
    abs_setup.absinfo.maximum = 255;
    ioctl(fd, UI_ABS_SETUP, &abs_setup);

    /* 设备描述 */
    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_VIRTUAL;
    usetup.id.vendor = 0xCA7F;
    usetup.id.product = 0x0001;
    usetup.id.version = 1;
    snprintf(usetup.name, UINPUT_MAX_NAME_SIZE, "catfee_vtouch");
    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        logf_msg("UI_DEV_SETUP 失败: %s", strerror(errno));
        close(fd);
        return -3;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        logf_msg("UI_DEV_CREATE 失败: %s", strerror(errno));
        close(fd);
        return -4;
    }

    /* 等系统 InputReader 识别新设备 (热插拔窗口) */
    sleep_ms(250);
    logf_msg("虚拟触屏已创建: %dx%d", g_screen_w, g_screen_h);
    return fd;
}

/**
 * 功能: 从 wm size 探测屏幕分辨率
 * 参数: 无
 * 返回: 无 (就地更新 g_screen_w / g_screen_h)
 * 使用场景: 设备分辨率各不相同, uinput 轴范围必须与之一致
 */
static void detect_screen_size(void) {
    char *out = run_cmd("wm size 2>/dev/null", NULL);
    if (!out) return;
    /* 形如: Physical size: 1080x2400  /  Override size: 720x1600 */
    char *p = strstr(out, "Override size:");
    if (!p) p = strstr(out, "Physical size:");
    if (p) {
        char *colon = strchr(p, ':');
        if (colon) {
            int w = 0, h = 0;
            if (sscanf(colon + 1, " %dx%d", &w, &h) == 2 && w > 0 && h > 0) {
                g_screen_w = w;
                g_screen_h = h;
            }
        }
    }
    free(out);
}

/* ---- 原始事件写入 ---- */

static void emit(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) {
        /* 写入失败仅记录, 不中断整个流程 */
        logf_msg("write input_event 失败: %s", strerror(errno));
    }
}

static void emit_sync(int fd) { emit(fd, EV_SYN, SYN_REPORT, 0); }

/**
 * 功能: 单点按下并保持 (内部使用, 调用方需持锁)
 * 参数: fd uinput 句柄; x,y 屏幕坐标; slot 触点编号
 * 返回: 无
 */
static void touch_down(int fd, int x, int y, int slot) {
    int tid = g_tracking_id++;
    if (g_tracking_id > 60000) g_tracking_id = 100;
    emit(fd, EV_ABS, ABS_MT_SLOT, slot);
    emit(fd, EV_ABS, ABS_MT_TRACKING_ID, tid);
    emit(fd, EV_KEY, BTN_TOUCH, 1);
    emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
    emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
    emit(fd, EV_ABS, ABS_X, x);
    emit(fd, EV_ABS, ABS_Y, y);
    emit(fd, EV_ABS, ABS_MT_PRESSURE, 200);
    emit(fd, EV_ABS, ABS_MT_TOUCH_MAJOR, 8);
    emit_sync(fd);
}

/**
 * 功能: 单点抬起 (内部使用, 调用方需持锁)
 * 参数: fd uinput 句柄; slot 触点编号
 * 返回: 无
 */
static void touch_up(int fd, int slot) {
    emit(fd, EV_ABS, ABS_MT_SLOT, slot);
    emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
    emit(fd, EV_KEY, BTN_TOUCH, 0);
    emit_sync(fd);
}

/* ========================= 原子操作实现 ========================= */

/**
 * 功能: 点击指定坐标
 * 参数: x,y 屏幕绝对坐标; hold_ms 按住时长(>=30 才稳定被识别)
 * 返回: 0=成功, 负值=失败
 * 使用场景: 点击按钮、列表项、文字链
 */
static int op_tap(int x, int y, int hold_ms) {
    if (g_uinput_fd < 0) return -1;
    if (hold_ms < 30) hold_ms = 30;
    pthread_mutex_lock(&g_input_lock);
    touch_down(g_uinput_fd, x, y, 0);
    sleep_ms(hold_ms);
    touch_up(g_uinput_fd, 0);
    pthread_mutex_unlock(&g_input_lock);
    return 0;
}

/**
 * 功能: 双指点击(双击)
 * 参数: x,y 坐标; gap_ms 两次点击间隔, 建议 80~120
 * 返回: 0=成功
 * 注意: 中间不能插入 MOVE 事件, 否则被识别为拖拽
 */
static int op_double_tap(int x, int y, int gap_ms) {
    if (g_uinput_fd < 0) return -1;
    if (gap_ms <= 0) gap_ms = 90;
    /* 两次点击必须在同一把锁内完成, 否则并发请求会插进中间事件, 被识别为拖拽 */
    pthread_mutex_lock(&g_input_lock);
    touch_down(g_uinput_fd, x, y, 0);
    sleep_ms(40);
    touch_up(g_uinput_fd, 0);
    sleep_ms(gap_ms);
    touch_down(g_uinput_fd, x, y, 0);
    sleep_ms(40);
    touch_up(g_uinput_fd, 0);
    pthread_mutex_unlock(&g_input_lock);
    return 0;
}

/**
 * 功能: 滑动
 * 参数: x1,y1 起点; x2,y2 终点; dur_ms 总时长
 * 返回: 0=成功
 * 关键点: 按约 10ms 一帧插值发送坐标, 直接跳起终点会丢事件
 */
static int op_swipe(int x1, int y1, int x2, int y2, int dur_ms) {
    if (g_uinput_fd < 0) return -1;
    if (dur_ms < 50) dur_ms = 50;
    const int frame_ms = 10;
    int frames = dur_ms / frame_ms;
    if (frames < 2) frames = 2;
    if (frames > 300) frames = 300;

    pthread_mutex_lock(&g_input_lock);
    touch_down(g_uinput_fd, x1, y1, 0);
    for (int i = 1; i <= frames; i++) {
        int cx = x1 + (x2 - x1) * i / frames;
        int cy = y1 + (y2 - y1) * i / frames;
        emit(g_uinput_fd, EV_ABS, ABS_MT_POSITION_X, cx);
        emit(g_uinput_fd, EV_ABS, ABS_MT_POSITION_Y, cy);
        emit(g_uinput_fd, EV_ABS, ABS_X, cx);
        emit(g_uinput_fd, EV_ABS, ABS_Y, cy);
        emit_sync(g_uinput_fd);
        sleep_ms(frame_ms);
    }
    touch_up(g_uinput_fd, 0);
    pthread_mutex_unlock(&g_input_lock);
    return 0;
}

/**
 * 功能: 发送物理按键事件
 * 参数: keycode Linux KEY_* 码 (如 158=BACK, 102=HOME, 187=APP_SWITCH)
 * 返回: 0=成功
 */
static int op_keyevent(int keycode) {
    if (g_uinput_fd < 0) return -1;
    pthread_mutex_lock(&g_input_lock);
    emit(g_uinput_fd, EV_KEY, keycode, 1);
    emit_sync(g_uinput_fd);
    sleep_ms(40);
    emit(g_uinput_fd, EV_KEY, keycode, 0);
    emit_sync(g_uinput_fd);
    pthread_mutex_unlock(&g_input_lock);
    return 0;
}

/**
 * 功能: 截图并落盘 (原始 RGBA 格式, 比 PNG 编码快 200ms+)
 * 参数: path 输出路径(NULL 用默认); out_w/out_h 回填尺寸; out_size 回填文件字节数
 * 返回: 0=成功, 负值=失败
 * 关键点: Android 12 的 screencap 头部含 colorspace 字段共 16 字节,
 *         老版本为 12 字节, 用文件总长度反推校验
 * 使用场景: 找图找色、Agent 视觉输入、失败存证
 */
static int op_shot(const char *path, int *out_w, int *out_h, long *out_size) {
    const char *dst = (path && *path) ? path : DEFAULT_SHOT_PATH;
    char cmd[1024];
    snprintf(cmd, sizeof(cmd), "screencap %s 2>/dev/null", dst);
    int rc = system(cmd);
    if (rc != 0) {
        logf_msg("screencap 执行失败, rc=%d", rc);
        return -1;
    }
    int fd = open(dst, O_RDONLY);
    if (fd < 0) {
        logf_msg("打开截图文件失败: %s", strerror(errno));
        return -2;
    }
    unsigned char hdr[16];
    memset(hdr, 0, sizeof(hdr));
    ssize_t n = read(fd, hdr, sizeof(hdr));
    off_t fsize = lseek(fd, 0, SEEK_END);
    close(fd);
    if (n < 12) return -3;

    uint32_t w, h, fmt;
    memcpy(&w, hdr, 4);
    memcpy(&h, hdr + 4, 4);
    memcpy(&fmt, hdr + 8, 4);
    if (w == 0 || h == 0 || w > 20000 || h > 20000) {
        logf_msg("截图头部非法: %ux%u", w, h);
        return -4;
    }

    /*
     * 用文件长度反推头部长度:
     *   Android 12 及以上 screencap 为 16 字节头(含 colorspace), 老版本为 12 字节。
     *   必须严格匹配, 否则按错误偏移解析会整体错行(表现为花屏)。
     *   32 位 ABI 下 long 可能溢出, 统一用 int64_t 计算。
     */
    int64_t pixel_bytes = (int64_t)w * (int64_t)h * 4LL;
    int64_t expected12 = 12LL + pixel_bytes;
    int64_t expected16 = 16LL + pixel_bytes;
    int header_len;
    if ((int64_t)fsize == expected12) {
        header_len = 12;
    } else if ((int64_t)fsize == expected16) {
        header_len = 16;
    } else {
        /* 尺寸与像素个数对不上, 说明是带 stride 的特殊格式或截图中途失败, 不能假装成功 */
        logf_msg("截图尺寸校验失败: %ux%u, 文件 %lld 字节, 期望 %lld 或 %lld",
                 w, h, (long long)fsize, (long long)expected12, (long long)expected16);
        return -5;
    }

    if (out_w) *out_w = (int)w;
    if (out_h) *out_h = (int)h;
    if (out_size) *out_size = (long)fsize;

    logf_msg("截图完成: %ux%u fmt=%u 头长=%d 大小=%ld", w, h, fmt, header_len, (long)fsize);
    /* 全黑帧提示: DRM/FLAG_SECURE 页面无法截图 */
    return header_len; /* 返回头长度, >0 即成功 */
}

/* ========================= JSON 输出辅助 ========================= */

/**
 * 功能: 将字符串转义为合法 JSON 字符串内容
 * 参数: src 源串; dst 目标缓冲; dst_cap 缓冲容量
 * 返回: 实际写入长度
 * 使用场景: shell 输出、错误信息回传给 App
 */
static size_t json_escape(const char *src, char *dst, size_t dst_cap) {
    size_t o = 0;
    if (!src) src = "";
    for (size_t i = 0; src[i] && o + 8 < dst_cap; i++) {
        unsigned char c = (unsigned char)src[i];
        switch (c) {
            case '"':  dst[o++] = '\\'; dst[o++] = '"';  break;
            case '\\': dst[o++] = '\\'; dst[o++] = '\\'; break;
            case '\n': dst[o++] = '\\'; dst[o++] = 'n';  break;
            case '\r': dst[o++] = '\\'; dst[o++] = 'r';  break;
            case '\t': dst[o++] = '\\'; dst[o++] = 't';  break;
            default:
                if (c < 0x20) {
                    o += (size_t)snprintf(dst + o, dst_cap - o, "\\u%04x", c);
                } else {
                    dst[o++] = (char)c;
                }
        }
    }
    dst[o] = '\0';
    return o;
}

/* ========================= 请求分发 ========================= */

/**
 * 功能: 解析并执行一条请求, 输出 JSON 响应
 * 参数: req 请求行; resp 响应缓冲; resp_cap 缓冲容量
 * 返回: 无 (结果写入 resp)
 * 使用场景: socket 服务与命令行单次调用共用
 */
static void dispatch(char *req, char *resp, size_t resp_cap) {
    /* 去掉换行 */
    size_t rl = strlen(req);
    while (rl > 0 && (req[rl - 1] == '\n' || req[rl - 1] == '\r')) req[--rl] = '\0';
    if (rl == 0) {
        snprintf(resp, resp_cap, "{\"id\":0,\"ok\":false,\"err\":\"空请求\"}");
        return;
    }

    /* 拆分: id|method|args... */
    char *saveptr = NULL;
    char *id_s = strtok_r(req, "|", &saveptr);
    char *method = strtok_r(NULL, "|", &saveptr);
    long id = id_s ? strtol(id_s, NULL, 10) : 0;
    if (!method) {
        snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"缺少 method\"}", id);
        return;
    }

    if (strcmp(method, "ping") == 0) {
        snprintf(resp, resp_cap,
                 "{\"id\":%ld,\"ok\":true,\"data\":{\"pong\":true,\"w\":%d,\"h\":%d}}",
                 id, g_screen_w, g_screen_h);
        return;
    }

    if (strcmp(method, "screen") == 0) {
        snprintf(resp, resp_cap,
                 "{\"id\":%ld,\"ok\":true,\"data\":{\"w\":%d,\"h\":%d}}",
                 id, g_screen_w, g_screen_h);
        return;
    }

    if (strcmp(method, "tap") == 0) {
        char *a1 = strtok_r(NULL, "|", &saveptr);
        char *a2 = strtok_r(NULL, "|", &saveptr);
        char *a3 = strtok_r(NULL, "|", &saveptr);
        if (!a1 || !a2) {
            snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"tap 需要 x,y\"}", id);
            return;
        }
        int x = atoi(a1), y = atoi(a2), hold = a3 ? atoi(a3) : 40;
        int rc = op_tap(x, y, hold);
        snprintf(resp, resp_cap,
                 "{\"id\":%ld,\"ok\":%s,\"err\":\"%s\"}",
                 id, rc == 0 ? "true" : "false", rc == 0 ? "" : "uinput 未初始化");
        return;
    }

    if (strcmp(method, "longpress") == 0) {
        char *a1 = strtok_r(NULL, "|", &saveptr);
        char *a2 = strtok_r(NULL, "|", &saveptr);
        char *a3 = strtok_r(NULL, "|", &saveptr);
        int x = a1 ? atoi(a1) : 0, y = a2 ? atoi(a2) : 0, hold = a3 ? atoi(a3) : 800;
        int rc = op_tap(x, y, hold);
        snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":%s}", id, rc == 0 ? "true" : "false");
        return;
    }

    if (strcmp(method, "doubletap") == 0) {
        char *a1 = strtok_r(NULL, "|", &saveptr);
        char *a2 = strtok_r(NULL, "|", &saveptr);
        int x = a1 ? atoi(a1) : 0, y = a2 ? atoi(a2) : 0;
        int rc = op_double_tap(x, y, 90);
        snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":%s}", id, rc == 0 ? "true" : "false");
        return;
    }

    if (strcmp(method, "swipe") == 0) {
        char *a[5];
        for (int i = 0; i < 5; i++) a[i] = strtok_r(NULL, "|", &saveptr);
        if (!a[3]) {
            snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"swipe 需要 x1,y1,x2,y2\"}", id);
            return;
        }
        int x1 = atoi(a[0]), y1 = atoi(a[1]), x2 = atoi(a[2]), y2 = atoi(a[3]);
        int dur = a[4] ? atoi(a[4]) : 300;
        int rc = op_swipe(x1, y1, x2, y2, dur);
        snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":%s}", id, rc == 0 ? "true" : "false");
        return;
    }

    if (strcmp(method, "keyevent") == 0) {
        char *a1 = strtok_r(NULL, "|", &saveptr);
        if (!a1) {
            snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"keyevent 需要 keycode\"}", id);
            return;
        }
        int rc = op_keyevent(atoi(a1));
        snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":%s}", id, rc == 0 ? "true" : "false");
        return;
    }

    if (strcmp(method, "shot") == 0) {
        char *a1 = strtok_r(NULL, "|", &saveptr);
        int w = 0, h = 0;
        long sz = 0;
        int rc = op_shot(a1, &w, &h, &sz);
        if (rc > 0) {
            char esc[1024];
            json_escape(a1 ? a1 : DEFAULT_SHOT_PATH, esc, sizeof(esc));
            snprintf(resp, resp_cap,
                     "{\"id\":%ld,\"ok\":true,\"data\":{\"path\":\"%s\",\"w\":%d,\"h\":%d,"
                     "\"size\":%ld,\"header\":%d}}",
                     id, esc, w, h, sz, rc);
        } else {
            snprintf(resp, resp_cap,
                     "{\"id\":%ld,\"ok\":false,\"err\":\"截图失败 rc=%d\"}", id, rc);
        }
        return;
    }

    if (strcmp(method, "shell") == 0) {
        /* strtok_r 用空分隔符不会跳过分隔符, 必须手工跳过前导 '|', 否则命令带前缀导致 popen 语法错误 */
        char *cmd = saveptr;
        if (cmd) {
            while (*cmd == '|') cmd++;
        }
        if (!cmd || *cmd == '\0') {
            snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"shell 需要命令\"}", id);
            return;
        }
        size_t out_len = 0;
        char *out = run_cmd(cmd, &out_len);
        if (!out) {
            snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"命令执行失败\"}", id);
            return;
        }
        /* 输出可能很大, 截断到 4KB 以内避免撑爆响应缓冲 */
        if (out_len > 4000) out[4000] = '\0';
        char *esc = (char *)malloc(out_len * 2 + 16);
        if (!esc) {
            free(out);
            snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"内存不足\"}", id);
            return;
        }
        json_escape(out, esc, out_len * 2 + 16);
        snprintf(resp, resp_cap,
                 "{\"id\":%ld,\"ok\":true,\"data\":{\"out\":\"%s\"}}", id, esc);
        free(esc);
        free(out);
        return;
    }

    snprintf(resp, resp_cap, "{\"id\":%ld,\"ok\":false,\"err\":\"未知方法\"}", id);
}

/* ========================= Socket 服务 ========================= */

/* 每个连接一个线程, 结构简单可靠; 连接数极少 (只有 App 一个) */
struct client_arg {
    int fd;
};

static void *client_thread(void *arg) {
    int cfd = ((struct client_arg *)arg)->fd;
    free(arg);
    char line[MAX_LINE];
    char resp[MAX_LINE];

    FILE *fp = fdopen(cfd, "r+");
    if (!fp) {
        close(cfd);
        return NULL;
    }
    setvbuf(fp, NULL, _IOLBF, 0);

    while (fgets(line, sizeof(line), fp)) {
        if (line[0] == '\0') continue;
        dispatch(line, resp, sizeof(resp));
        fprintf(fp, "%s\n", resp);
        fflush(fp);
    }
    fclose(fp);
    return NULL;
}

/**
 * 功能: 启动 Unix Socket 常驻服务
 * 参数: sock_path socket 文件路径
 * 返回: 0=成功
 * 使用场景: daemon 常驻模式入口
 */
static int serve(const char *sock_path) {
    unlink(sock_path);
    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) {
        logf_msg("创建 socket 失败: %s", strerror(errno));
        return -1;
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", sock_path);

    if (bind(sfd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        logf_msg("bind 失败: %s", strerror(errno));
        close(sfd);
        return -2;
    }
    /* 允许 App 进程连接 */
    chmod(sock_path, 0666);
    if (listen(sfd, 8) < 0) {
        logf_msg("listen 失败: %s", strerror(errno));
        close(sfd);
        return -3;
    }
    logf_msg("daemon 已就绪: %s", sock_path);

    while (1) {
        int cfd = accept(sfd, NULL, NULL);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            logf_msg("accept 失败: %s", strerror(errno));
            sleep_ms(200);
            continue;
        }
        struct client_arg *ca = (struct client_arg *)malloc(sizeof(*ca));
        if (!ca) {
            close(cfd);
            continue;
        }
        ca->fd = cfd;
        pthread_t th;
        if (pthread_create(&th, NULL, client_thread, ca) != 0) {
            logf_msg("创建线程失败: %s", strerror(errno));
            close(cfd);
            free(ca);
            continue;
        }
        pthread_detach(th);
    }
    return 0;
}

/* ========================= stdio 常驻服务 ========================= */

/**
 * 功能: 从 stdin 逐行读指令、向 stdout 写响应, 供 App 通过 root shell 常驻交互
 * 参数: 无
 * 返回: 0=stdin 关闭后正常退出
 * 使用场景:
 *   Android App 受 SELinux 限制无法直连 root 域创建的 socket,
 *   因此采用「su 常驻进程 + stdio 协议」的方式, 兼具低延迟与免 fork。
 *   启动命令示例:
 *     catfee_daemon stdio 2>/data/local/tmp/catfee_daemon.log
 */
static int serve_stdio(void) {
    char line[MAX_LINE];
    char resp[MAX_LINE];
    logf_msg("daemon stdio 模式就绪: %dx%d", g_screen_w, g_screen_h);
    while (fgets(line, sizeof(line), stdin)) {
        if (line[0] == '\0' || line[0] == '\n') continue;
        dispatch(line, resp, sizeof(resp));
        fputs(resp, stdout);
        fputc('\n', stdout);
        fflush(stdout);
    }
    logf_msg("stdin 已关闭, daemon 退出");
    return 0;
}

/* ========================= 入口 ========================= */

/*
 * 功能: 进程入口
 * 参数:
 *   argv[1] 为空或 "serve"  -> Unix Socket 常驻服务
 *   argv[1] == "stdio"      -> stdin/stdout 常驻服务 (App 推荐方式)
 *   其他                    -> 单次执行, 如 catfee_daemon tap 540 1200
 * 返回: 退出码
 */
int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);

    detect_screen_size();
    g_uinput_fd = uinput_init();
    if (g_uinput_fd < 0) {
        logf_msg("虚拟触屏初始化失败, 输入类操作将不可用");
    }

    /* stdio 常驻模式 */
    if (argc >= 2 && strcmp(argv[1], "stdio") == 0) {
        int rc = serve_stdio();
        if (g_uinput_fd >= 0) close(g_uinput_fd);
        return rc;
    }

    /* 单次执行模式 */
    if (argc >= 2 && strcmp(argv[1], "serve") != 0) {
        char req[MAX_LINE];
        char resp[MAX_LINE];
        int n = snprintf(req, sizeof(req), "1|%s", argv[1]);
        for (int i = 2; i < argc && n > 0 && (size_t)n < sizeof(req) - 2; i++) {
            n += snprintf(req + n, sizeof(req) - (size_t)n, "|%s", argv[i]);
        }
        dispatch(req, resp, sizeof(resp));
        printf("%s\n", resp);
        if (g_uinput_fd >= 0) close(g_uinput_fd);
        return 0;
    }

    /* 常驻模式 */
    const char *sock_path = DEFAULT_SOCK_PATH;
    if (argc >= 3) sock_path = argv[2];
    int rc = serve(sock_path);
    if (g_uinput_fd >= 0) close(g_uinput_fd);
    return rc == 0 ? 0 : 2;
}
