package com.catfee.rootauto.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 无 Root 的应用/链接启动入口: PackageManager + Intent。
 * Root 可用时也优先走这里, 避免依赖 am/monkey。
 */
object AppLauncher {

    fun launchPackage(context: Context, packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return "包名为空"
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return "找不到 $pkg 的可启动入口（可能未安装或没有 LAUNCHER Activity）"
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            "启动 $pkg 失败: ${e.message}"
        }
    }

    fun launchActivity(context: Context, component: String): String? {
        val raw = component.trim()
        if (raw.isEmpty()) return "组件名为空"
        return try {
            val cn = ComponentName.unflattenFromString(raw)
                ?: return "组件格式不正确，应为 包名/Activity 全名"
            val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            "启动 Activity 失败: ${e.message}"
        }
    }

    fun openUrl(context: Context, url: String): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return "URL 为空"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            "打开链接失败: ${e.message}"
        }
    }
}
