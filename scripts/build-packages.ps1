[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$JavaHome = Join-Path $RootDir '_tools\jdk-17.0.20+8'
$AndroidHome = Join-Path $RootDir '_tools\android-sdk'
$Gradle = Join-Path $RootDir '_tools\gradle-8.9\bin\gradle.bat'
$ApkSigner = Join-Path $AndroidHome 'build-tools\35.0.0\apksigner.bat'
$WindowsBuilder = Join-Path $RootDir 'windows\build.ps1'
$DockerBuilder = Join-Path $RootDir 'docker\build-image.ps1'
$PackDir = Join-Path $RootDir 'pack'

$ApkOutput = Join-Path $PackDir 'WOL-Android.apk'
$WatchOutput = Join-Path $PackDir 'WOL-Watch.apk'
$WindowsOutput = Join-Path $PackDir 'WOL-Proxy-Windows.exe'
$DockerOutput = Join-Path $PackDir 'WOL-Proxy-Docker.tar'
$LinuxOutput = Join-Path $PackDir 'WOL-Proxy-Linux.tar'

function Assert-File([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description 不存在：$Path"
    }
}

function Invoke-Checked([string]$Executable, [object[]]$Arguments, [string]$Description) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description 失败，退出码=$LASTEXITCODE。"
    }
}

Assert-File $Gradle 'Gradle'
Assert-File $ApkSigner 'APK 签名校验工具'
Assert-File $WindowsBuilder 'Windows 打包脚本'
Assert-File $DockerBuilder 'Docker 镜像打包脚本'

$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidHome
$env:PATH = "$JavaHome\bin;$AndroidHome\platform-tools;$AndroidHome\cmdline-tools\latest\bin;$env:PATH"

New-Item -ItemType Directory -Path $PackDir -Force | Out-Null

Write-Host '正在构建 Android APK、代理 JAR 和 Linux 分发包...'
Invoke-Checked $Gradle @(
    ':app:assembleDebug',
    ':watch:assembleRelease',
    ':wol-proxy:jar',
    ':wol-proxy:distTar',
    '--no-daemon'
) 'Gradle 构建'

$BuiltApk = Join-Path $RootDir 'apk\build\outputs\apk\debug\app-debug.apk'
$BuiltWatch = Join-Path $RootDir 'watch\build\outputs\apk\release\watch-release.apk'
$BuiltLinuxTar = Join-Path $RootDir 'proxy\build\distributions\wol-proxy.tar'
Assert-File $BuiltApk 'Android APK'
Assert-File $BuiltWatch '手表 APK'
Assert-File $BuiltLinuxTar 'Linux 分发包'
Copy-Item -LiteralPath $BuiltApk -Destination $ApkOutput -Force
Copy-Item -LiteralPath $BuiltWatch -Destination $WatchOutput -Force
Copy-Item -LiteralPath $BuiltLinuxTar -Destination $LinuxOutput -Force
Invoke-Checked $ApkSigner @('verify', '--verbose', $ApkOutput) 'APK 签名校验'
Invoke-Checked $ApkSigner @('verify', '--verbose', $WatchOutput) '手表 APK 签名校验'

Write-Host '正在构建 Windows 便携 EXE...'
& $WindowsBuilder -OutputFile $WindowsOutput
if ($LASTEXITCODE -ne 0) {
    throw "Windows 便携 EXE 构建失败，退出码=$LASTEXITCODE。"
}

Write-Host '正在构建 Docker 镜像 TAR...'
& $DockerBuilder -OutputFile $DockerOutput
if ($LASTEXITCODE -ne 0) {
    throw "Docker 镜像构建失败，退出码=$LASTEXITCODE。"
}

Assert-File $ApkOutput '正式 APK'
Assert-File $WatchOutput '正式手表 APK'
Assert-File $WindowsOutput '正式 Windows EXE'
Assert-File $DockerOutput '正式 Docker 镜像'
Assert-File $LinuxOutput '正式 Linux 分发包'

$allowedNames = @(
    [IO.Path]::GetFileName($ApkOutput),
    [IO.Path]::GetFileName($WatchOutput),
    [IO.Path]::GetFileName($WindowsOutput),
    [IO.Path]::GetFileName($DockerOutput),
    [IO.Path]::GetFileName($LinuxOutput)
)
$resolvedPack = [IO.Path]::GetFullPath($PackDir)
$expectedPack = [IO.Path]::GetFullPath((Join-Path $RootDir 'pack'))
if (-not $resolvedPack.Equals($expectedPack, [StringComparison]::OrdinalIgnoreCase)) {
    throw "pack 目录校验失败：$resolvedPack"
}
Get-ChildItem -LiteralPath $resolvedPack -Force | Where-Object { $_.Name -notin $allowedNames } | ForEach-Object {
    if ($_.PSIsContainer) {
        [IO.Directory]::Delete($_.FullName, $true)
    } else {
        [IO.File]::Delete($_.FullName)
    }
}

Write-Host ''
Write-Host '发行产物已生成：'
Write-Host "  APK:     $ApkOutput"
Write-Host "  Watch:   $WatchOutput"
Write-Host "  Windows: $WindowsOutput"
Write-Host "  Docker:  $DockerOutput"
Write-Host "  Linux:   $LinuxOutput"
