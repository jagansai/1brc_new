param(
    [string]$version = "sai",  # pass 'baseline' to run the baseline class
    [string[]]$appArgs = @(),
    [switch]$parallel,
    [string]$measurements = "",  # optional path to measurements file; passed as -Dmeasurements=path
    [string]$javaArgs = "-Xms2G -Xmx4G  -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+UseG1GC -Xlog:gc*:file=gc.log:time,uptime,level", # JVM args to pass before the main class
    [int]$blockSize = 0,  # optional block size; passed as -DblockSize=size
    [switch]$jfr,
    [string]$jfrDir = "."  # directory to write JFR recordings when -jfr is specified
)

$mode = if ($parallel) { 'parallel' } else { 'sequential' }
$class = if ($version -eq 'baseline') { 'dev.morling.onebrc.CalculateAverage_baseline' } else { 'dev.morling.onebrc.CalculateAverage' }
$outFile = if ($version -eq 'baseline') { "baseline_output_${mode}.txt" } else { "sai_output_${mode}.txt" }

if (Test-Path $outFile) {
    Remove-Item $outFile
    Write-Host "Deleted old file: $outFile"
}

Write-Host "Running class: $class -> $outFile"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$jvmArgsArray = if ([string]::IsNullOrWhiteSpace($javaArgs)) { @() } else { ($javaArgs -split '\s+') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } }

# If JFR profiling is requested, allocate a time-stamped recording filename and add StartFlightRecording JVM arg
if ($jfr) {
    if (-not (Test-Path $jfrDir)) {
        try { New-Item -ItemType Directory -Path $jfrDir -Force | Out-Null } catch { Write-Host "Failed to create JFR directory $jfrDir" -ForegroundColor Yellow }
    }
    $timestamp = (Get-Date).ToString('yyyyMMdd_HHmmss')
    $jfrFile = Join-Path (Resolve-Path $jfrDir) "jfr_${version}_${mode}_${timestamp}.jfr"
    Write-Host "JFR profiling enabled — recording will be written to: $jfrFile"
    # Use the standard StartFlightRecording option; dumponexit ensures the file is written when the JVM exits
    $jvmArgsArray += "-XX:StartFlightRecording=filename=$jfrFile,dumponexit=true,settings=profile"
}

# System properties to pass before JVM args (e.g. -Dmeasurements)
$sysProps = @()
if (-not [string]::IsNullOrWhiteSpace($measurements)) {
    $sysProps += "-Dmeasurements=$measurements"
}
if ($blockSize -gt 0) {
    $sysProps += "-DblockSize=$blockSize"
}


& java @sysProps @jvmArgsArray --class-path ".\target\1brc_new-1.0-SNAPSHOT.jar" $class $mode @appArgs >> $outFile 2>&1
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
    }
    catch {
        $outContent = "";
    }
}
else {
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
    }
    else {
        Write-Host "(no output file)" -ForegroundColor Red
    }
}

if ((Test-Path $baselineFile) -and (Test-Path $saiFile)) {
    Write-Host "Comparing $baselineFile and $saiFile"
    # Helper: for sai outputs ignore preamble up to 'End of configuration.' if present
    function Get-Content-AfterMarker {
        param([string]$path)
        # Read file lines and, if present, strip everything up to and including the first
        # line that matches 'End of configuration' (case-insensitive, optional trailing period).
        try {
            $lines = Get-Content $path -ErrorAction Stop
        } catch {
            return @()
        }
        $regex = '^(?i)End of configuration\.?$'
        $match = $lines | Select-String -Pattern $regex | Select-Object -First 1
        if ($match) {
            $start = $match.LineNumber
            # Return lines after the matched line
            if ($start -lt $lines.Length) { return $lines[$start..($lines.Length - 1)] } else { return @() }
        }
        return $lines
    }

    $left = Get-Content-AfterMarker -path $baselineFile
    $right = Get-Content-AfterMarker -path $saiFile
    Compare-Object $left $right
} else {
    Write-Host "One or both output files missing; skipping comparison."
}