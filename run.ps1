param(
    [string]$version = "sai",  # pass 'baseline' to run the baseline class
    [string[]]$appArgs = @(),
    [switch]$parallel,
    [string]$measurements = "",  # optional path to measurements file; passed as -Dmeasurements=path
    [string]$javaArgs = "-Xms2G -Xmx4G  -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+UseG1GC -Xlog:gc*:file=gc.log:time,uptime,level" # JVM args to pass before the main class
)

$mode = if ($parallel) { 'parallel' } else { 'sequential' }
$class = if ($version -eq 'baseline') { 'dev.morling.onebrc.CalculateAverage_baseline' } else { 'dev.morling.onebrc.CalculateAverage' }
$outFile = if ($version -eq 'baseline') { "baseline_output_${mode}.txt" } else { "sai_output_${mode}.txt" }

Write-Host "Running class: $class -> $outFile"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
 $jvmArgsArray = if ([string]::IsNullOrWhiteSpace($javaArgs)) { @() } else { ($javaArgs -split '\s+') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } }

if ([string]::IsNullOrWhiteSpace($measurements)) {
    & java @jvmArgsArray --class-path ".\target\1brc_new-1.0-SNAPSHOT.jar" $class $mode @appArgs >> $outFile 2>&1
} else {
    # pass measurements as a JVM system property before the main class
    & java -Dmeasurements="$measurements" @jvmArgsArray --class-path ".\target\1brc_new-1.0-SNAPSHOT.jar" $class $mode @appArgs >> $outFile 2>&1
}
$sw.Stop()
Write-Host ("Elapsed: {0}" -f $sw.Elapsed)

$baselineFile = "baseline_output_${mode}.txt"
$saiFile = "sai_output_${mode}.txt"

# Basic post-run validation: inspect the output file for common Java errors
# or for the expected measurement pattern (min/mean/max like 1.0/2.0/3.0).
$outContent = ""
if (Test-Path $outFile) {
    try {
        $outContent = Get-Content $outFile -Raw -ErrorAction Stop
    } catch {
        $outContent = "";
    }
} else {
    Write-Host "Warning: output file $outFile not created." -ForegroundColor Red
}

$failed = $false
# detect explicit JVM/class errors
if ($outContent -match 'Error: Could not find or load main class' -or $outContent -match 'Caused by: java.lang.ClassNotFoundException' -or $outContent -match 'Exception in thread') {
    $failed = $true
}

# Accept as success if we see per-city measurement triples like 12.3/45.6/78.9
if (-not ($outContent -match '\d+\.\d+\/\d+\.\d+\/\d+\.\d+')) {
    # If no obvious numeric triples, treat as failure unless the output contains the "Using measurements file" line
    if (-not ($outContent -match 'Using measurements file')) {
        $failed = $true
    }
}

if ($failed) {
    Write-Host "Run appears to have failed for $outFile" -ForegroundColor Red
    Write-Host "---- Last 20 lines of $outFile ----" -ForegroundColor Red
    if (Test-Path $outFile) {
        Get-Content $outFile -Tail 20 | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    } else {
        Write-Host "(no output file)" -ForegroundColor Red
    }
}

if ((Test-Path $baselineFile) -and (Test-Path $saiFile)) {
    Write-Host "Comparing $baselineFile and $saiFile"
    Compare-Object (Get-Content $baselineFile) (Get-Content $saiFile)
} else {
    Write-Host "One or both output files missing; skipping comparison."
}