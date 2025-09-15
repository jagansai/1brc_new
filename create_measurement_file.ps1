param(
    [Parameter(Mandatory=$true)]
    [int]$Lines,
    [string]$Source = "measurements.txt",
    [string]$Target = "measurements.sample.txt"
)

Write-Host "Creating sample file: $Target (first $Lines lines from $Source)"

$inStream = [System.IO.File]::OpenRead($Source)
$reader = New-Object System.IO.StreamReader($inStream)
$outStream = [System.IO.File]::Open($Target, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
$writer = New-Object System.IO.StreamWriter($outStream)

$written = 0
try {
    while ($written -lt $Lines) {
        if ($reader.EndOfStream) { break }
        $line = $reader.ReadLine()
        $writer.WriteLine($line)
        $written++
        if (($written % 100000) -eq 0) { Write-Host "Written $written lines..." }
    }
} finally {
    $writer.Flush(); $writer.Close(); $outStream.Close()
    $reader.Close(); $inStream.Close()
}

Write-Host "Done. Wrote $written lines to $Target"