param(
    [Parameter(Mandatory=$true)][string]$InputManifest,
    [Parameter(Mandatory=$true)][string]$OutputFile,
    [Parameter(Mandatory=$true)][string]$Language
)
$ErrorActionPreference = 'Stop'
$stage = 'initialize'
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType=WindowsRuntime]
    $null = [Windows.Globalization.Language, Windows.Globalization, ContentType=WindowsRuntime]
    $null = [Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime]
    $null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
    $null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
    $null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType=WindowsRuntime]
    $null = [Windows.Media.Ocr.OcrResult, Windows.Foundation, ContentType=WindowsRuntime]
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and
        $_.GetGenericArguments().Count -eq 1 -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    function Wait-WinRT($Operation, [Type]$ResultType) {
        $task = $asTask.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
        $task.GetAwaiter().GetResult()
    }
    function Hash-File([string]$Path) {
        # WinRT runs in Windows PowerShell. A parent PowerShell 7 module path may
        # omit Get-FileHash; use the framework hash directly, without loading a profile.
        $hash = [Security.Cryptography.SHA256]::Create()
        $stream = [IO.File]::OpenRead($Path)
        try { ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose(); $hash.Dispose() }
    }
    $stage = 'manifest'
    $inputBytes = [IO.File]::ReadAllBytes($InputManifest)
    if ($inputBytes.Length -gt 65536 -or (Test-Path -LiteralPath $OutputFile)) { throw 'invalid_input_or_existing_output' }
    $manifest = [Text.Encoding]::UTF8.GetString($inputBytes) | ConvertFrom-Json
    if ($manifest.contractVersion -ne 1 -or @($manifest.pages).Count -lt 1 -or @($manifest.pages).Count -gt 5) { throw 'invalid_page_manifest' }
    $stage = 'language'
    $available = @([Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages | ForEach-Object { $_.LanguageTag })
    if ($Language -notin $available) { throw 'language_not_installed' }
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($Language))
    if ($null -eq $engine -or $engine.RecognizerLanguage.LanguageTag -ne $Language) { throw 'ocr_engine_not_available' }
    $pages = @()
    foreach ($page in $manifest.pages) {
        $stage = 'image_open'
        if ((Hash-File $page.image) -ne $page.imageSha256) { throw 'rendered_page_digest_changed' }
        $file = Wait-WinRT ([Windows.Storage.StorageFile]::GetFileFromPathAsync([IO.Path]::GetFullPath($page.image))) ([Windows.Storage.StorageFile])
        $stream = Wait-WinRT ($file.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
        $bitmap = $null
        try {
            $decoder = Wait-WinRT ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
            if ($decoder.PixelWidth -gt [Windows.Media.Ocr.OcrEngine]::MaxImageDimension -or $decoder.PixelHeight -gt [Windows.Media.Ocr.OcrEngine]::MaxImageDimension) { throw 'ocr_image_dimensions' }
            $bitmap = Wait-WinRT ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
            $stage = 'recognition'
            $result = Wait-WinRT ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
            $lines = @($result.Lines | ForEach-Object {
                [ordered]@{ text=$_.Text; words=@($_.Words | ForEach-Object {
                    [ordered]@{ text=$_.Text; x=$_.BoundingRect.X; y=$_.BoundingRect.Y; width=$_.BoundingRect.Width; height=$_.BoundingRect.Height }
                }) }
            })
            $pages += [ordered]@{ page=[int]$page.page; imageSha256=$page.imageSha256; width=[int]$decoder.PixelWidth; height=[int]$decoder.PixelHeight; lines=$lines }
        } finally {
            if ($null -ne $bitmap) { $bitmap.Dispose() }
            $stream.Dispose()
        }
        if ((Hash-File $page.image) -ne $page.imageSha256) { throw 'rendered_page_digest_changed' }
    }
    $stage = 'receipt'
    $record = [ordered]@{
        contractVersion=1; engine='Windows.Media.Ocr'; osVersion=[Environment]::OSVersion.Version.ToString();
        language=$engine.RecognizerLanguage.LanguageTag; maxImageDimension=[Windows.Media.Ocr.OcrEngine]::MaxImageDimension;
        sourceSha256=$manifest.sourceSha256; pages=$pages; execution='ACTUAL_LOCAL_ENGINE'; networkUsed=$false
    }
    $json = $record | ConvertTo-Json -Depth 16
    $output = [IO.File]::Open($OutputFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($json + "`n")
        $output.Write($bytes, 0, $bytes.Length); $output.Flush($true)
    } finally { $output.Dispose() }
    Write-Output ('{"status":"PASS","engine":"Windows.Media.Ocr","pages":' + $pages.Count + '}')
} catch {
    # Recognition text, paths and provider data must not enter diagnostic output.
    # Stage and script line are fixed code metadata. Never emit exception text,
    # recognized text, filesystem paths or engine/provider message contents.
    [Console]::Error.WriteLine((@{status='FAIL';errorCode='local_windows_ocr_failed';stage=$stage;scriptLine=[int]$_.InvocationInfo.ScriptLineNumber} | ConvertTo-Json -Compress))
    exit 1
}
