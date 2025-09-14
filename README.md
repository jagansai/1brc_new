
# 1brc_new

This is a Maven project using Java 24. It contains the file `measurements.txt` for benchmarking or data processing tasks.

## Table of Contents
- [Attempt-1 (baseline vs Sai)](#attempt-1-baseline-vs-sai)
- [Project Structure](#project-structure)
- [Build](#build)
- [Requirements](#requirements)
- [Notes](#notes)

## Attempt-1 (Baseline vs Sai)

The following table documents the first set of runs comparing the baseline implementation (`CalculateAverage_baseline`) with the new implementation (`CalculateAverage`). Times below are wall-clock elapsed times produced by `run.ps1`.

| Mode | Parallel | Output file | Elapsed | Comparison |
|------|----------|-------------|---------|------------|
| baseline | False | baseline_output_sequential.txt | 00:03:20.1185032 | skipped |
| baseline | True | baseline_output_parallel.txt | 00:02:47.4549396 | skipped |
| sai | False | sai_output_sequential.txt | 00:03:41.5202825 | identical |
| sai | True | sai_output_parallel.txt | 00:02:52.7101181 | identical |


Notes:
- In the sequential baseline run the comparison was skipped because the corresponding Sai output was not present at that time.
- After running all four combinations the script compares `baseline_output_*.txt` and `sai_output_*.txt` when both files exist.

**`Implementation notes`**

- `CalculateAverage.java` reads `measurements.txt` line-by-line and parses each line by `;` into a city name and temperature. 
Lines are processed in parallel when invoked with the `-parallel` flag; each line updates its city's `Stats` using `compute` and a synchronized `accept(...)` method on the accumulator to ensure thread-safety with minimal allocation overhead. After processing the file, The values are sorted by city name and written to the output file.

## Project Structure
- Java source: `src/main/java`
- Tests: `src/test/java`
- Data: `measurements.txt`

## Build
To build the project, run:

```
mvn clean package
```

## Requirements
- Java 24
- Maven 3.9+

## Notes
- Update this README as the project evolves.



- The baseline implementation (`CalculateAverage_baseline`) was modified only to support parallel execution — no other functional changes were made. The comparison between baseline and Sai is therefore focused on performance and parallel behavior, not algorithmic differences.

## Capture machine configuration (PowerShell)

To record the machine configuration used for the runs (CPU, memory, OS, Java and Maven versions) into `machine_config.txt`, run the following PowerShell snippet in the project root:

```powershell
Get-CimInstance Win32_ComputerSystem | Select-Object Manufacturer, Model, TotalPhysicalMemory | Out-File machine_config.txt
Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors | Out-File -Append machine_config.txt
Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version | Out-File -Append machine_config.txt
java -version 2>&1 | Out-File -Append machine_config.txt
mvn -v 2>&1 | Out-File -Append machine_config.txt
```

This writes a small report to `machine_config.txt` you can include with benchmarking results.

## Attempt-2 (Custom parsing improvements)

The following runs were performed on the full measurements file with the optimized `CalculateAverage` implementation that uses a custom ASCII double parser to reduce per-line allocations and parsing overhead.

| Mode | Parallel | Output file | Elapsed | Comparison |
|------|----------|-------------|---------|------------|
| sai | True | sai_output_parallel.txt | 00:02:45.4430233 | skipped |
| sai | False | sai_output_sequential.txt | 00:02:51.4015248 | skipped |
| baseline | True | baseline_output_parallel.txt | 00:03:07.7177463 | identical |
| baseline | False | baseline_output_sequential.txt | 00:03:43.3887907 | identical |

And the same data in the alternate compact table format produced by `run_all.ps1`:

Mode     Parallel OutFile                        Elapsed          Comparison
----     -------- -------                        -------          ----------
sai          True sai_output_parallel.txt        00:02:45.4430233 skipped
sai         False sai_output_sequential.txt      00:02:51.4015248 skipped
baseline     True baseline_output_parallel.txt   00:03:07.7177463 identical
baseline    False baseline_output_sequential.txt 00:03:43.3887907 identical

Note: these Attempt-2 runs used a custom parsing routine in `CalculateAverage.java` that replaces the original String.split + `Double.parseDouble` path with an indexOf-based city split and an ASCII double parser. That change significantly reduces short-lived String and boxed-number allocations and reduced elapsed times on sequential runs.
