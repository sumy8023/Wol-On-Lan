[CmdletBinding()]
param(
    [string]$JarFile = (Join-Path $PSScriptRoot '..\proxy\build\libs\wol-proxy.jar'),
    [string]$ConfigFile = (Join-Path $PSScriptRoot 'data\config.yml'),
    [string]$OutputFile = (Join-Path $PSScriptRoot '..\pack\WOL-Proxy-Docker-Image.tar'),
    [string]$ImageName = 'wol-proxy:1.0.2',
    [string]$BaseImage = 'eclipse-temurin:17-jre-jammy',
    [string]$CraneFile = (Join-Path $PSScriptRoot '..\_tools\crane-v0.21.9\crane.exe')
)

$ErrorActionPreference = 'Stop'

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

Assert-File $JarFile '代理 JAR'
Assert-File $ConfigFile '配置模板'
Assert-File $CraneFile 'crane 工具'

$resolvedOutput = [IO.Path]::GetFullPath($OutputFile)
$outputParent = Split-Path -Parent $resolvedOutput
New-Item -ItemType Directory -Path $outputParent -Force | Out-Null

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('wol-crane-' + [guid]::NewGuid().ToString('N'))
$layerRoot = Join-Path $tempRoot 'layer'
$unpackRoot = Join-Path $tempRoot 'unpack'
New-Item -ItemType Directory -Path (Join-Path $layerRoot 'app'), (Join-Path $layerRoot 'config'), $unpackRoot -Force | Out-Null

try {
    $layerTar = Join-Path $tempRoot 'app-layer.tar'
    $appendedTar = Join-Path $tempRoot 'appended.tar'
    $sourceConfig = Join-Path $tempRoot 'source-config.json'

    Copy-Item -LiteralPath $JarFile -Destination (Join-Path $layerRoot 'app\wol-proxy.jar') -Force
    Copy-Item -LiteralPath $ConfigFile -Destination (Join-Path $layerRoot 'config\config.yml') -Force
    Invoke-Checked 'tar.exe' @('-C', $layerRoot, '-cf', $layerTar, 'app', 'config') '应用层打包'

    Invoke-Checked $CraneFile @(
        '--platform', 'linux/amd64', 'append',
        '--base', $BaseImage,
        '--new_layer', $layerTar,
        '--new_tag', $ImageName,
        '--output', $appendedTar
    ) '基础镜像拉取和应用层追加'

    $manifestText = (& tar.exe -xOf $appendedTar manifest.json | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "读取基础镜像 manifest.json 失败，退出码=$LASTEXITCODE。"
    }
    $manifestItems = @($manifestText | ConvertFrom-Json)
    if ($manifestItems.Count -ne 1) {
        throw "当前只支持单平台镜像，实际 manifest 条目数=$($manifestItems.Count)。"
    }
    $manifestItem = $manifestItems[0]
    $configEntry = [string]$manifestItem.Config
    if ([string]::IsNullOrWhiteSpace($configEntry)) {
        throw '镜像 manifest 缺少 Config 条目。'
    }

    # 只解出 manifest 和 layer，避开 Windows 文件系统不能直接创建带冒号的旧 Config 文件名。
    $extractArgs = @('-xf', $appendedTar, '-C', $unpackRoot, 'manifest.json')
    $extractArgs += @($manifestItem.Layers)
    Invoke-Checked 'tar.exe' $extractArgs '基础镜像内容解包'

    & tar.exe -xOf $appendedTar $configEntry | Set-Content -LiteralPath $sourceConfig -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "读取镜像配置 $configEntry 失败，退出码=$LASTEXITCODE。"
    }
    $imageConfig = Get-Content -LiteralPath $sourceConfig -Raw | ConvertFrom-Json
    if ($null -eq $imageConfig.config) {
        $imageConfig | Add-Member -MemberType NoteProperty -Name config -Value ([pscustomobject]@{})
    }
    $runtimeConfig = $imageConfig.config
    $runtimeConfig | Add-Member -MemberType NoteProperty -Name Entrypoint -Value ([string[]]@(
        'java', '-Duser.home=/config', '-Dwol.proxy.docker=true', '-cp', '/app/wol-proxy.jar', 'com.example.wolproxy.Main'
    )) -Force
    $runtimeConfig | Add-Member -MemberType NoteProperty -Name Cmd -Value ([string[]]@(
        '--config', '/config/config.yml'
    )) -Force
    $runtimeConfig | Add-Member -MemberType NoteProperty -Name WorkingDir -Value '/app' -Force
    $runtimeConfig | Add-Member -MemberType NoteProperty -Name Volumes -Value ([ordered]@{
        '/config' = [ordered]@{}
    }) -Force
    $runtimeConfig | Add-Member -MemberType NoteProperty -Name ExposedPorts -Value ([ordered]@{
        '14250/tcp' = [ordered]@{}
    }) -Force

    $configJson = $imageConfig | ConvertTo-Json -Depth 100 -Compress
    $configPath = Join-Path $unpackRoot 'config.json'
    $utf8NoBom = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($configPath, $configJson, $utf8NoBom)

    # Docker load accepts an arbitrary Config filename; using config.json avoids a Windows ':' filename.
    $manifestItem.Config = 'config.json'
    $manifestJson = ConvertTo-Json -InputObject @($manifestItem) -Depth 20 -Compress
    [IO.File]::WriteAllText((Join-Path $unpackRoot 'manifest.json'), $manifestJson, $utf8NoBom)

    if (Test-Path -LiteralPath $resolvedOutput) {
        Remove-Item -LiteralPath $resolvedOutput -Force
    }
    Push-Location $unpackRoot
    try {
        Invoke-Checked 'tar.exe' @('-cf', $resolvedOutput, '*') 'Docker load tar 导出'
    }
    finally {
        Pop-Location
    }

    Invoke-Checked $CraneFile @('validate', '--tarball', $resolvedOutput) 'Docker 镜像校验'
    $finalManifest = @((& tar.exe -xOf $resolvedOutput manifest.json | Out-String) | ConvertFrom-Json)
    $finalConfig = (& tar.exe -xOf $resolvedOutput config.json | Out-String) | ConvertFrom-Json
    if ($finalManifest.Count -ne 1 -or [string]$finalManifest[0].RepoTags[0] -ne $ImageName) {
        throw '最终镜像 RepoTags 校验失败。'
    }
    $entrypoint = @($finalConfig.config.Entrypoint) -join '|'
    $cmd = @($finalConfig.config.Cmd) -join '|'
    if ($entrypoint -ne 'java|-Duser.home=/config|-Dwol.proxy.docker=true|-cp|/app/wol-proxy.jar|com.example.wolproxy.Main' -or
        $cmd -ne '--config|/config/config.yml' -or
        [string]$finalConfig.config.WorkingDir -ne '/app' -or
        $null -eq $finalConfig.config.Volumes.'/config' -or
        $null -eq $finalConfig.config.ExposedPorts.'14250/tcp') {
        throw '最终镜像 ENTRYPOINT/CMD/工作目录/端口元数据校验失败。'
    }
    Write-Host "Docker 镜像已生成：$resolvedOutput"
    Write-Host "标签=$ImageName，平台=linux/amd64，大小=$((Get-Item -LiteralPath $resolvedOutput).Length) 字节"
    Write-Host 'ENTRYPOINT=java -Duser.home=/config -Dwol.proxy.docker=true -cp /app/wol-proxy.jar com.example.wolproxy.Main'
    Write-Host 'CMD=--config /config/config.yml'
}
finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
