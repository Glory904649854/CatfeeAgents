##########################################################################
# build-magisk.ps1 —— 生成可直接刷入的 Magisk 模块 zip
#
# 功能描述:
#   把 magisk/ 目录下的脚本与 daemon/dist 下的二进制打包成 Magisk 可识别的 zip。
#   产物: dist/CatfeeRootAuto-v<版本>.zip
#
# 使用场景:
#   不通过 App 内一键安装, 而是手动在 Magisk App 里刷入时使用。
#
# 修改时间: 2026-11-03
##########################################################################

param(
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $root "dist/_stage"
$outDir = Join-Path $root "dist"
$zip = Join-Path $outDir "CatfeeRootAuto-v$Version.zip"

Write-Host "[1/4] 清理临时目录"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host "[2/4] 复制模块文件"
Copy-Item "$root/magisk/module.prop"   $stage
Copy-Item "$root/magisk/customize.sh"  $stage
Copy-Item "$root/magisk/service.sh"    $stage

Write-Host "[3/4] 复制 daemon 二进制"
foreach ($abi in @("arm64-v8a", "armeabi-v7a")) {
    $src = Join-Path $root "daemon/dist/$abi/catfee_daemon"
    if (-not (Test-Path $src)) {
        Write-Warning "缺少 $abi 的 daemon，请先执行 daemon/build.ps1"
        continue
    }
    $dst = Join-Path $stage "lib/$abi"
    New-Item -ItemType Directory -Force -Path $dst | Out-Null
    Copy-Item $src $dst
}

Write-Host "[4/4] 打包 zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Add-Type -AssemblyName System.IO.Compression.FileSystem
# 注意: 必须手动写入条目并强制使用正斜杠,
#       .NET Framework 的 CreateFromDirectory 在 Windows 上会写反斜杠, 导致 Magisk 解压失败
$archive = [System.IO.Compression.ZipFile]::Open($zip, 'Create')
try {
    Get-ChildItem $stage -Recurse -File | ForEach-Object {
        $rel = $_.FullName.Substring($stage.Length + 1).Replace('\', '/')
        $entry = $archive.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
        $entry.LastWriteTime = $_.LastWriteTime
        $es = $entry.Open()
        try {
            $fs = [System.IO.File]::OpenRead($_.FullName)
            try { $fs.CopyTo($es) } finally { $fs.Dispose() }
        } finally { $es.Dispose() }
    }
} finally {
    $archive.Dispose()
}

Remove-Item -Recurse -Force $stage
Write-Host "完成: $zip"
