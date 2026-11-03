param([Parameter(Mandatory=$true)][string]$Bundle,
      [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
function Digest([string]$Path) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($Path)
    try { [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $algorithm.Dispose() }
}
function New-Json([string]$Path, $Value) {
    $stream = [IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 30))
        $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true)
    } finally { $stream.Dispose() }
}
$bundleRoot = [IO.Path]::GetFullPath($Bundle)
$manifestPath = Join-Path $bundleRoot 'BUNDLE.json'
$seal = [IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json
if ($seal.contractVersion -ne 1 -or $seal.kind -ne 'hxai_office_compatibility_bundle' -or
    @($seal.jobs).Count -lt 1 -or @($seal.jobs).Count -gt 20) { throw 'invalid_bundle' }
$labels = @{}
foreach ($job in $seal.jobs) {
    if ($job.label -cnotmatch '^[a-zA-Z0-9_-]{1,80}$' -or $labels.ContainsKey($job.label) -or
        $job.kind -cnotmatch '^(docx|xlsx|pptx)$' -or
        $job.source -cne ('inputs/' + $job.label + '.' + $job.kind)) { throw 'invalid_source_identity' }
    $labels[$job.label] = $true
    $source = Join-Path $bundleRoot $job.source
    if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'source_reparse_point' }
    if ((Digest $source) -cne $job.sourceSha256) { throw 'source_digest_mismatch' }
}
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputRoot) { throw 'existing_output_directory' }
[void][IO.Directory]::CreateDirectory($outputRoot)
$results = @(); $failed = $false
foreach ($job in $seal.jobs) {
    $source = Join-Path $bundleRoot $job.source
    $saved = Join-Path $outputRoot ($job.label + '.' + $job.kind)
    $pdf = Join-Path $outputRoot ($job.label + '.pdf')
    $app = $null; $doc = $null; $reopened = $null; $operations = @(); $version = $null; $errorCode = $null
    $prog = @{docx='KWPS.Application';xlsx='KET.Application';pptx='KWPP.Application'}[$job.kind]
    try {
        $app = New-Object -ComObject $prog
        $count = switch ($job.kind) { docx {$app.Documents.Count}; xlsx {$app.Workbooks.Count}; pptx {$app.Presentations.Count} }
        if ([int]$count -ne 0) { throw 'automation_session_not_empty' }
        $version = [string]$app.Version
        # Only the new empty automation application, never an attached user document.
        if ($job.kind -eq 'docx') {
            $app.DisplayAlerts = 0
            $doc = $app.Documents.Open([string]$source,$false,$true,$false); $operations += 'OPEN'
            $doc.SaveAs2([string]$saved,16); $operations += 'SAVE_AS'
            $doc.Close(0); $doc = $null
            $reopened = $app.Documents.Open([string]$saved,$false,$true,$false); $operations += 'REOPEN'
            $reopened.ExportAsFixedFormat([string]$pdf,17); $operations += 'EXPORT_PDF'
            $reopened.Close(0); $reopened = $null
        } elseif ($job.kind -eq 'xlsx') {
            $app.DisplayAlerts = $false
            $doc = $app.Workbooks.Open([string]$source,0,$false); $operations += 'OPEN'
            $app.CalculateFullRebuild(); $operations += 'FULL_RECALCULATE'
            $doc.SaveAs([string]$saved,51); $operations += 'SAVE_AS'
            $doc.Close($false); $doc = $null
            $reopened = $app.Workbooks.Open([string]$saved,0,$true); $operations += 'REOPEN'
            $reopened.ExportAsFixedFormat(0,[string]$pdf); $operations += 'EXPORT_PDF'
            $reopened.Close($false); $reopened = $null
        } else {
            $doc = $app.Presentations.Open([string]$source,$true,$false,$false); $operations += 'OPEN'
            $doc.SaveAs([string]$saved,24); $operations += 'SAVE_AS'
            $doc.Close(); $doc = $null
            $reopened = $app.Presentations.Open([string]$saved,$true,$false,$false); $operations += 'REOPEN'
            # Export all logical pages in a temporary read-only rendering view.
            # The saved file keeps its original hidden flags; this view is never saved.
            for ($i=1; $i -le [int]$reopened.Slides.Count; $i++) {
                $slide = $reopened.Slides.Item($i); $slide.SlideShowTransition.Hidden = 0
                [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($slide)
            }
            $reopened.SaveAs([string]$pdf,32); $operations += 'EXPORT_PDF'
            $reopened.Close(); $reopened = $null
        }
        if ((Digest $source) -cne $job.sourceSha256) { throw 'source_changed' }
        if (-not (Test-Path -LiteralPath $saved) -or -not (Test-Path -LiteralPath $pdf)) { throw 'missing_output' }
    } catch {
        $failed = $true
        $errorCode = if ($_.Exception.Message -in @('automation_session_not_empty','source_changed','missing_output')) {
            $_.Exception.Message
        } else { 'wps_operation_failed' }
        # Public synthetic inputs only; method/type identify unsupported native API, no document text.
        New-Json (Join-Path $outputRoot ($job.label + '-failure.json')) ([ordered]@{
            errorCode=$errorCode;exceptionType=$_.Exception.GetType().FullName;
            hresult=$_.Exception.HResult;scriptLine=$_.InvocationInfo.ScriptLineNumber;completedOperations=$operations
        })
    } finally {
        # Close only explicit document objects opened above; never Quit a possibly shared process.
        foreach ($owned in @($reopened,$doc)) {
            if ($null -ne $owned) {
                try { if ($job.kind -eq 'pptx') { $owned.Close() } else { $owned.Close(0) } } catch { }
                try { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($owned) } catch { }
            }
        }
        if ($null -ne $app) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($app) }
    }
    $results += [ordered]@{label=$job.label;kind=$job.kind;programmaticId=$prog;engineVersion=$version;
        sourceSha256=$job.sourceSha256;saved=($job.label+'.'+$job.kind);pdf=($job.label+'.pdf');
        savedSha256=$(if(Test-Path -LiteralPath $saved){Digest $saved}else{$null});
        pdfSha256=$(if(Test-Path -LiteralPath $pdf){Digest $pdf}else{$null});operations=$operations;errorCode=$errorCode}
    New-Json (Join-Path $outputRoot ($job.label + '-execution.json')) $results[-1]
}
New-Json (Join-Path $outputRoot 'WPS_EXECUTION.json') ([ordered]@{
    contractVersion=1;engine='WPS';execution='ACTUAL_LOCAL_AUTOMATION_EMPTY_SESSION';
    bundleSha256=(Digest $manifestPath);scope='FIXED_SYNTHETIC_FILES_ONLY; independent readback and visual inspection separate';jobs=$results
})
New-Json (Join-Path $outputRoot 'ENGINE_RECORD.json') ([ordered]@{
    contractVersion=1;engine='WPS';engineVersion=(@($results | ForEach-Object {$_.engineVersion} | Select-Object -Unique) -join '/');
    origin='EXTERNAL_UNATTESTED';bundleSha256=(Digest $manifestPath);jobs=$results
})
if ($failed) { exit 1 }
