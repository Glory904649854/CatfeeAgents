##########################################################################
# build.ps1 —— 编译 daemon 二进制 (arm64-v8a / armeabi-v7a)
#
# 功能描述:
#   调用 NDK clang 交叉编译 catfee_daemon.c，产物输出到 daemon/dist/<abi>/
#
# 参数说明:
#   -ApiLevel 最低 API 级别，默认 26（Android 8.0，与 App minSdk 一致）
#
# 使用场景:
#   修改 C 源码后重新编译；App 构建前会自动把 dist 内容复制进 assets。
#
# 修改时间: 2026-11-03
##########################################################################

param(
    [int]$ApiLevel = 26
)

$ErrorActionPreference = "Stop"

$ndkRoot = $env:ANDROID_NDK_HOME
if (-not $ndkRoot) {
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { $sdk = "C:\Android\sdk" }
    $ndks = Get-ChildItem "$sdk/ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending
    if (-not $ndks) { throw "未找到 NDK，请设置 ANDROID_NDK_HOME 或用 sdkmanager 安装" }
    $ndkRoot = $ndks[0].FullName
}

$bin = "$ndkRoot/toolchains/llvm/prebuilt/windows-x86_64/bin"
$src = "$PSScriptRoot/src/catfee_daemon.c"
$dist = "$PSScriptRoot/dist"

$targets = @(
    @{ Triple = "aarch64-linux-android$ApiLevel";       Abi = "arm64-v8a" },
    @{ Triple = "armv7a-linux-androideabi$ApiLevel";    Abi = "armeabi-v7a" }
)

foreach ($t in $targets) {
    $cc = "$bin/$($t.Triple)-clang.cmd"
    if (-not (Test-Path $cc)) { Write-Warning "找不到编译器: $cc"; continue }
    $outDir = "$dist/$($t.Abi)"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $out = "$outDir/catfee_daemon"
    Write-Host "编译 $($t.Abi) ..."
    & $cc -O2 -Wall -o $out $src
    if ($LASTEXITCODE -ne 0) { throw "编译 $($t.Abi) 失败" }
    Write-Host "  产物: $out  ($((Get-Item $out).Length) 字节)"
}

Write-Host "全部完成"
