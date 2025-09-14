# Runs the 4 combinations (baseline/sai x sequential/parallel)
# Saves per-run elapsed time and output comparison status to attempt1_results.md


# delete old output files if present
$oldFiles = @("baseline_output_sequential.txt", "baseline_output_parallel.txt", "sai_output_sequential.txt", "sai_output_parallel.txt")
foreach ($f in $oldFiles) {
    if (Test-Path $f) {
        Remove-Item $f
        Write-Host "Deleted old file: $f"
    }
}


$combinations = @(
    @{mode='baseline'; parallel=$false},
    @{mode='baseline'; parallel=$true},
    @{mode='sai';      parallel=$false},
    @{mode='sai';      parallel=$true}
)

$results = @()

foreach ($c in $combinations) {
    $mode = $c.mode
    $par = $c.parallel
    $suffix = if ($par) { 'parallel' } else { 'sequential' }
    $outFile = if ($mode -eq 'baseline') { "baseline_output_${suffix}.txt" } else { "sai_output_${suffix}.txt" }

    Write-Host "Running $mode (parallel=$par) -> $outFile"

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    if ($par) {
        & .\run.ps1 -version $mode -parallel
    } else {
        & .\run.ps1 -version $mode
    }
    $sw.Stop()

    $elapsed = $sw.Elapsed.ToString()

    $baselineFile = "baseline_output_${suffix}.txt"
    $saiFile = "sai_output_${suffix}.txt"
    $existsBaseline = Test-Path $baselineFile
    $existsSai = Test-Path $saiFile
    if ($existsBaseline -and $existsSai) {
        $cmpRaw = Compare-Object (Get-Content $baselineFile) (Get-Content $saiFile) | Out-String
        $cmp = $cmpRaw.Trim()
        if ($cmp -eq '') { $comparison = 'identical' } else { $comparison = 'diff' }
    } else {
        $comparison = 'skipped'
    }

    $results += [pscustomobject]@{
        Mode = $mode
        Parallel = $par
        OutFile = $outFile
        Elapsed = $elapsed
        Comparison = $comparison
    }
}

# Write results to markdown
$md = @()
$md += "# Attempt-1 results"
$md += ""
$md += "| Mode | Parallel | Output file | Elapsed | Comparison |"
$md += "|------|----------|-------------|---------|------------|"
foreach ($r in $results) {
    $md += "| $($r.Mode) | $($r.Parallel) | $($r.OutFile) | $($r.Elapsed) | $($r.Comparison) |"
}

$md | Out-default 
