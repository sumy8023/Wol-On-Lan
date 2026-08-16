[CmdletBinding()]
param(
    [string]$OutputFile
)

$ErrorActionPreference = 'Stop'

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ProxyDir = Join-Path $RootDir 'proxy'
$LauncherDir = Join-Path $PSScriptRoot 'launcher'
$JavaHome = (Resolve-Path (Join-Path $RootDir '_tools\jdk-17.0.20+8')).Path
$Gradle = Join-Path $RootDir '_tools\gradle-8.9\bin\gradle.bat'
$JPackage = Join-Path $JavaHome 'bin\jpackage.exe'
$IconGenerator = Join-Path $LauncherDir 'generate-icon.ps1'
$IconFile = Join-Path $LauncherDir 'app-icon.ico'
if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $RootDir 'pack\WOL-Proxy-Windows.exe'
}
$OutputFile = [IO.Path]::GetFullPath($OutputFile)
$Stamp = Get-Date -Format 'yyyyMMddHHmmss'
$InputDir = Join-Path $RootDir ("build\package\windows-portable-input-" + $Stamp)
$AppDest = Join-Path $RootDir ("build\package\windows-portable-app-" + $Stamp)
$JPackageTemp = Join-Path $RootDir ("build\package\windows-portable-jpackage-temp-" + $Stamp)
$PublishDir = Join-Path $RootDir 'build\package\windows-portable-launcher'

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

New-Item -ItemType Directory -Path $InputDir,$AppDest,$JPackageTemp,$PublishDir,(Split-Path $OutputFile -Parent) -Force | Out-Null

& $IconGenerator -OutputFile $IconFile

& $Gradle ':wol-proxy:installDist' '--no-daemon'
if ($LASTEXITCODE -ne 0) {
    throw '代理服务编译失败。'
}

Copy-Item (Join-Path $ProxyDir 'build\libs\wol-proxy.jar') (Join-Path $InputDir 'wol-proxy.jar') -Force
Copy-Item (Join-Path $ProxyDir 'config.example.yml') (Join-Path $InputDir 'wol-config.example.yml') -Force
Copy-Item (Join-Path $ProxyDir 'README.md') (Join-Path $InputDir 'README.md') -Force

& $JPackage `
    '--type' 'app-image' `
    '--input' $InputDir `
    '--main-jar' 'wol-proxy.jar' `
    '--main-class' 'com.example.wolproxy.Main' `
    '--name' 'WOL-Proxy' `
    '--app-version' '1.0.2' `
    '--vendor' 'WOL' `
    '--description' 'WOL 网络唤醒代理服务' `
    '--icon' $IconFile `
    '--dest' $AppDest `
    '--temp' $JPackageTemp `
    '--win-console'
if ($LASTEXITCODE -ne 0) {
    throw '代理运行时镜像生成失败。'
}

$PayloadFile = Join-Path $AppDest 'wol-proxy-payload.zip'
& tar.exe '-a' '-c' '-f' $PayloadFile '-C' $AppDest 'WOL-Proxy'
if ($LASTEXITCODE -ne 0) {
    throw '便携运行时载荷生成失败。'
}

$PayloadPath = (Resolve-Path $PayloadFile).Path
& dotnet publish (Join-Path $LauncherDir 'WolProxyLauncher.csproj') `
    '-c' 'Release' `
    '-r' 'win-x64' `
    '--self-contained' 'true' `
    ('-p:PayloadPath=' + $PayloadPath) `
    '-p:PublishSingleFile=true' `
    '-p:IncludeNativeLibrariesForSelfExtract=true' `
    '-p:EnableCompressionInSingleFile=true' `
    '-o' $PublishDir `
    '--nologo'
if ($LASTEXITCODE -ne 0) {
    throw '便携单文件 EXE 生成失败。'
}

Copy-Item (Join-Path $PublishDir 'WOL-Proxy-Windows.exe') $OutputFile -Force
Write-Host "便携单文件 EXE 已生成：$OutputFile"
