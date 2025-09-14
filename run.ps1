param(
    [string]$version = "sai",  # pass 'baseline' to run the baseline class
    [switch]$parallel
)

$mode = if ($parallel) { 'parallel' } else { 'sequential' }
$class = if ($version -eq 'baseline') { 'dev.morling.onebrc.CalculateAverage_baseline' } else { 'dev.morling.onebrc.CalculateAverage' }
$outFile = if ($version -eq 'baseline') { "baseline_output_${mode}.txt" } else { "sai_output_${mode}.txt" }

Write-Host "Running class: $class -> $outFile"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
& java --class-path ".\target\1brc_new-1.0-SNAPSHOT.jar" $class $mode > $outFile 2>&1
$sw.Stop()
Write-Host ("Elapsed: {0}" -f $sw.Elapsed)

$baselineFile = "baseline_output_${mode}.txt"
$saiFile = "sai_output_${mode}.txt"

if ((Test-Path $baselineFile) -and (Test-Path $saiFile)) {
    Write-Host "Comparing $baselineFile and $saiFile"
    Compare-Object (Get-Content $baselineFile) (Get-Content $saiFile)
} else {
    Write-Host "One or both output files missing; skipping comparison."
}