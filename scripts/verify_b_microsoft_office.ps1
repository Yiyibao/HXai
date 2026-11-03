param(
    [Parameter(Mandatory=$true)][string]$InputManifest,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$InputManifest = [IO.Path]::GetFullPath($InputManifest)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
function Hash-File([string]$Path) {
    $stream = [IO.File]::OpenRead($Path); $algorithm = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
function Write-NewJson([string]$Path, $Value) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 16) + "`n"); $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
}
function Owned-Process([string]$Name) {
    # The caller verified that no such process existed before COM creation.
    # Word exposes no usable HWND before a document/window exists.
    $processes = @(Get-Process $Name -ErrorAction SilentlyContinue)
    if ($processes.Count -ne 1 -or $processes[0].Path -notlike '*Microsoft Office*') { throw 'not_unique_new_microsoft_instance' }
    return [int]$processes[0].Id
}
try {
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'output_exists' }
    $manifest = Get-Content -LiteralPath $InputManifest -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.contractVersion -ne 1 -or @($manifest.jobs).Count -notin 1..10) { throw 'invalid_manifest' }
    $null = New-Item -ItemType Directory -Path $OutputDirectory
    $rows = @()
    foreach ($job in $manifest.jobs) {
        $kind = [string]$job.kind; $label = [string]$job.label
        if ($kind -notin @('docx','xlsx','pptx') -or $label -notmatch '^[a-z0-9-]{1,64}$') { throw 'invalid_job' }
        $name = @{docx='WINWORD';xlsx='EXCEL';pptx='POWERPNT'}[$kind]
        if (@(Get-Process $name -ErrorAction SilentlyContinue).Count -ne 0) { throw 'existing_microsoft_user_instance' }
        if ((Hash-File $job.source) -ne $job.sourceSha256) { throw 'source_digest_mismatch' }
        $prog = @{docx='Word.Application';xlsx='Excel.Application';pptx='PowerPoint.Application'}[$kind]
        $output = Join-Path $OutputDirectory ($label + '.' + $kind)
        $pdf = Join-Path $OutputDirectory ($label + '.pdf')
        $app = $null; $doc = $null; $reopened = $null; $ownedId = $null; $data = $null; $summary = $null; $slide = $null
        try {
            $app = New-Object -ComObject $prog
            $ownedId = Owned-Process $name
            $version = [string]$app.Version
            $app.AutomationSecurity = 3
            if ($kind -eq 'docx') {
                $app.Visible = $false; $app.DisplayAlerts = 0
                $doc = $app.Documents.Open([string]$job.source, $false, $true, $false)
                $beforeEquations = [int]$doc.OMaths.Count
                $beforeText = [string]$doc.Content.Text
                $doc.SaveAs2([string]$output, 16)
                $doc.ExportAsFixedFormat([string]$pdf, 17)
                $doc.Close(0); $doc = $null
                $reopened = $app.Documents.Open([string]$output, $false, $true, $false)
                $metrics = [ordered]@{ equationsBefore=$beforeEquations; equationsAfter=[int]$reopened.OMaths.Count; textEqualAfterReopen=($beforeText -eq [string]$reopened.Content.Text); pages=[int]$reopened.ComputeStatistics(2); tables=[int]$reopened.Tables.Count }
                if (-not $metrics.textEqualAfterReopen -or $metrics.equationsAfter -ne $beforeEquations) { throw 'word_roundtrip_content_changed' }
                $reopened.Close(0); $reopened = $null
            } elseif ($kind -eq 'xlsx') {
                $app.Visible = $false; $app.DisplayAlerts = $false; $app.EnableEvents = $false; $app.AskToUpdateLinks = $false
                $doc = $app.Workbooks.Open([string]$job.source, 0, $false)
                $app.CalculateFullRebuild()
                $deadline = [DateTime]::UtcNow.AddSeconds(60)
                while ($app.CalculationState -ne 0) { if ([DateTime]::UtcNow -gt $deadline) { throw 'excel_calculation_timeout' }; Start-Sleep -Milliseconds 100 }
                $doc.SaveAs([string]$output, 51)
                $doc.ExportAsFixedFormat(0, [string]$pdf)
                $doc.Close($false); $doc = $null
                $reopened = $app.Workbooks.Open([string]$output, 0, $true)
                $data = $reopened.Worksheets.Item('Data'); $summary = $reopened.Worksheets.Item('Summary')
                $metrics = [ordered]@{ sheets=[int]$reopened.Worksheets.Count; dataA2=$data.Range('A2').Value2; dataB2=$data.Range('B2').Value2; dataB2Formula=$data.Range('B2').Formula; dataC2=$data.Range('C2').Value2; dataD2=$data.Range('D2').Value2; dataD2Text=$data.Range('D2').Text; dataE2=$data.Range('E2').Value2; summaryA1=$summary.Range('A1').Value2; summaryB1=$summary.Range('B1').Value2; summaryC1=$summary.Range('C1').Value2; summaryD1=$summary.Range('D1').Value2; summaryE1=$summary.Range('E1').Value2; summaryVisible=[int]$summary.Visible; charts=[int]$data.ChartObjects().Count }
                if ($metrics.dataA2 -ne 7 -or $metrics.dataB2 -ne 21 -or $metrics.dataE2 -ne 22 -or $metrics.summaryA1 -ne 22 -or $metrics.summaryB1 -ne 'sum=22' -or $metrics.summaryC1 -ne $true -or [Math]::Abs($metrics.summaryE1 - 0.3) -gt 1e-14 -or $metrics.summaryVisible -ne 0 -or $metrics.charts -ne 1 -or $metrics.dataC2 -cne '001') { throw 'excel_reopen_values_mismatch' }
                if ([string]$metrics.dataD2Text -match '#' -or [string]::IsNullOrWhiteSpace([string]$metrics.dataD2Text)) { throw 'excel_date_not_visibly_rendered' }
                $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($data); $data = $null
                $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($summary); $summary = $null
                $reopened.Close($false); $reopened = $null
            } else {
                $app.DisplayAlerts = 1
                $doc = $app.Presentations.Open([string]$job.source, -1, 0, 0)
                $before = @()
                foreach ($slide in $doc.Slides) {
                    $index = [int]$slide.SlideIndex
                    $text = @($slide.Shapes | Where-Object { $_.HasTextFrame -eq -1 } | ForEach-Object { $_.TextFrame.TextRange.Text }) -join "`n"
                    $slide.Export((Join-Path $OutputDirectory ($label + '-before-' + $index + '.png')), 'PNG', 1000, 563)
                    $before += [ordered]@{ logicalPage=$index; hidden=([int]$slide.SlideShowTransition.Hidden -eq -1); text=$text; shapes=[int]$slide.Shapes.Count }
                    $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($slide); $slide = $null
                }
                $doc.SaveCopyAs([string]$output, 24)
                $doc.Close(); $doc = $null
                $reopened = $app.Presentations.Open([string]$output, -1, 0, 0)
                $after = @()
                foreach ($slide in $reopened.Slides) {
                    $index = [int]$slide.SlideIndex
                    $text = @($slide.Shapes | Where-Object { $_.HasTextFrame -eq -1 } | ForEach-Object { $_.TextFrame.TextRange.Text }) -join "`n"
                    $slide.Export((Join-Path $OutputDirectory ($label + '-after-' + $index + '.png')), 'PNG', 1000, 563)
                    $after += [ordered]@{ logicalPage=$index; hidden=([int]$slide.SlideShowTransition.Hidden -eq -1); text=$text; shapes=[int]$slide.Shapes.Count }
                    $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($slide); $slide = $null
                }
                if (($before | ConvertTo-Json -Depth 6 -Compress) -cne ($after | ConvertTo-Json -Depth 6 -Compress)) { throw 'powerpoint_roundtrip_changed' }
                # Render all logical pages from an unsaved, read-only view only.
                # The saved PPTX retains the independently checked hidden flags.
                $savedHash = Hash-File $output
                foreach ($slide in $reopened.Slides) {
                    $slide.SlideShowTransition.Hidden = 0
                    $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($slide); $slide = $null
                }
                $printRange = $reopened.PrintOptions.Ranges.Add(1, $reopened.Slides.Count)
                try { $reopened.ExportAsFixedFormat([string]$pdf, 2, 1, 0, 1, 1, -1, $printRange, 4) }
                finally { $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($printRange); $printRange = $null }
                if ((Hash-File $output) -ne $savedHash) { throw 'render_view_modified_saved_presentation' }
                $metrics = [ordered]@{ slides=@($before).Count; allLogicalSlides=$after; allSlidesExportedIncludingHidden=$true; pdfRenderView='ALL_LOGICAL_SLIDES_UNHIDDEN_IN_MEMORY_ONLY'; savedPresentationUnchangedByRender=$true }
                $reopened.Close(); $reopened = $null
            }
            if ((Hash-File $job.source) -ne $job.sourceSha256) { throw 'immutable_source_changed' }
            $rows += [ordered]@{ label=$label; kind=$kind; engine=$prog; version=$version; ownedProcessId=$ownedId; sourceSha256=$job.sourceSha256; outputSha256=(Hash-File $output); pdfSha256=(Hash-File $pdf); metrics=$metrics }
        } finally {
            if ($null -ne $reopened) { try { $reopened.Close() } catch {} }
            if ($null -ne $doc) { try { $doc.Close() } catch {} }
            foreach ($reference in @($data, $summary, $slide, $reopened, $doc)) {
                if ($null -ne $reference) { $null = [Runtime.InteropServices.Marshal]::FinalReleaseComObject($reference) }
            }
            $data = $null; $summary = $null; $slide = $null; $reopened = $null; $doc = $null
            if ($null -ne $app -and $null -ne $ownedId) { $app.Quit() }
            if ($null -ne $app) { $null=[Runtime.InteropServices.Marshal]::FinalReleaseComObject($app) }
            [GC]::Collect(); [GC]::WaitForPendingFinalizers()
            # The parent waits for the owned PID after this COM-host process exits.
            # In-host RCWs/call-site caches can keep Excel alive despite Quit/Release.
        }
    }
    Write-NewJson (Join-Path $OutputDirectory 'OFFICE_RUN.json') ([ordered]@{ status='PASS'; engine='Actual Microsoft Office COM'; jobs=$rows; wpsAccessed=$false; shutdown='QUIT_REQUESTED_PARENT_MUST_VERIFY_AFTER_HOST_EXIT' })
    Write-Output ('{"status":"PASS","jobs":' + $rows.Count + ',"engine":"Microsoft Office"}')
} catch {
    if (Test-Path -LiteralPath $OutputDirectory) {
        $known = if ($_.Exception.Message -match '^[a-z_]{1,64}$') { $_.Exception.Message } else { 'microsoft_office_operation_failed' }
        Write-NewJson (Join-Path $OutputDirectory 'OFFICE_FAILURE.json') ([ordered]@{ status='FAIL'; type=$_.Exception.GetType().Name; scriptLine=$_.InvocationInfo.ScriptLineNumber; errorCode=$known; completedJobs=$rows })
    }
    [Console]::Error.WriteLine('{"status":"FAIL","errorCode":"microsoft_office_operation_failed"}')
    exit 1
}
