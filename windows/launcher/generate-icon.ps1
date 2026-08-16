param(
    [string]$OutputFile = '',
    [string]$PreviewFile = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing.Common

if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $PSScriptRoot 'app-icon.ico'
}

$sizes = @(16, 20, 24, 32, 40, 48, 64, 128, 256)
$images = foreach ($size in $sizes) {
    $bitmap = [System.Drawing.Bitmap]::new(
        $size,
        $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $inset = [single][Math]::Max(0.5, $size * 0.015)
    $diameter = [single]($size * 0.38)
    $extent = [single]($size - 2 * $inset)
    $backgroundPath = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $backgroundPath.AddArc($inset, $inset, $diameter, $diameter, 180, 90)
    $backgroundPath.AddArc($inset + $extent - $diameter, $inset, $diameter, $diameter, 270, 90)
    $backgroundPath.AddArc($inset + $extent - $diameter, $inset + $extent - $diameter, $diameter, $diameter, 0, 90)
    $backgroundPath.AddArc($inset, $inset + $extent - $diameter, $diameter, $diameter, 90, 90)
    $backgroundPath.CloseFigure()

    $blueBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#1D4ED8'))
    $graphics.FillPath($blueBrush, $backgroundPath)

    # 与 Android 矢量图使用相同的 24x24 电源图形和缩放比例。
    $powerPath = [System.Drawing.Drawing2D.GraphicsPath]::new([System.Drawing.Drawing2D.FillMode]::Winding)
    $powerPath.AddRectangle([System.Drawing.RectangleF]::new(11, 3, 2, 10))
    $powerPath.StartFigure()
    $powerPath.AddLine(17.83, 5.17, 16.41, 6.59)
    $powerPath.AddBezier(16.41, 6.59, 17.99, 7.86, 19, 9.82, 19, 12)
    $powerPath.AddBezier(19, 12, 19, 15.87, 15.87, 19, 12, 19)
    $powerPath.AddBezier(12, 19, 8.13, 19, 5, 15.87, 5, 12)
    $powerPath.AddBezier(5, 12, 5, 9.82, 6.01, 7.86, 7.59, 6.59)
    $powerPath.AddLine(7.59, 6.59, 6.17, 5.17)
    $powerPath.AddBezier(6.17, 5.17, 4.23, 6.82, 3, 9.26, 3, 12)
    $powerPath.AddBezier(3, 12, 3, 16.97, 7.03, 21, 12, 21)
    $powerPath.AddBezier(12, 21, 16.97, 21, 21, 16.97, 21, 12)
    $powerPath.AddBezier(21, 12, 21, 9.26, 19.77, 6.82, 17.83, 5.17)
    $powerPath.CloseFigure()

    $scale = [single]($size / 36.0)
    $translation = [single]($size / 6.0)
    $matrix = [System.Drawing.Drawing2D.Matrix]::new($scale, 0, 0, $scale, $translation, $translation)
    $powerPath.Transform($matrix)
    $whiteBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
    $graphics.FillPath($whiteBrush, $powerPath)

    $stream = [System.IO.MemoryStream]::new()
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $stream.ToArray()

    $stream.Dispose()
    $whiteBrush.Dispose()
    $matrix.Dispose()
    $powerPath.Dispose()
    $blueBrush.Dispose()
    $backgroundPath.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()

    [PSCustomObject]@{ Size = $size; Bytes = $bytes }
}

$outputDirectory = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($OutputFile))
[System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$fileStream = [System.IO.File]::Create($OutputFile)
$writer = [System.IO.BinaryWriter]::new($fileStream)
$writer.Write([uint16]0)
$writer.Write([uint16]1)
$writer.Write([uint16]$images.Count)

$offset = 6 + 16 * $images.Count
foreach ($image in $images) {
    $dimension = if ($image.Size -ge 256) { 0 } else { $image.Size }
    $writer.Write([byte]$dimension)
    $writer.Write([byte]$dimension)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([uint16]1)
    $writer.Write([uint16]32)
    $writer.Write([uint32]$image.Bytes.Length)
    $writer.Write([uint32]$offset)
    $offset += $image.Bytes.Length
}
foreach ($image in $images) {
    $writer.Write([byte[]]$image.Bytes)
}
$writer.Dispose()
$fileStream.Dispose()

if (-not [string]::IsNullOrWhiteSpace($PreviewFile)) {
    $previewDirectory = [System.IO.Path]::GetDirectoryName([System.IO.Path]::GetFullPath($PreviewFile))
    [System.IO.Directory]::CreateDirectory($previewDirectory) | Out-Null
    $preview = $images | Where-Object Size -eq 256 | Select-Object -First 1
    [System.IO.File]::WriteAllBytes($PreviewFile, [byte[]]$preview.Bytes)
}

Write-Host "Windows 图标已生成：$OutputFile"
