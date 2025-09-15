
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

Footnote: In this Attempt-1 run the `sai` and `baseline` outputs that were both present compared as identical; no clear faster implementation from these rows alone (see later attempts for performance improvements).

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
| baseline | False | baseline_output_sequential.txt | 00:03:43.3887907 | identical |
| baseline | True | baseline_output_parallel.txt | 00:03:07.7177463 | identical |
| sai | False | sai_output_sequential.txt | 00:02:51.4015248 | skipped |
| sai | True | sai_output_parallel.txt | 00:02:45.4430233 | skipped |

And the same data in the alternate compact table format produced by `run_all.ps1`:


Note: these Attempt-2 runs used a custom parsing routine in `CalculateAverage.java` that replaces the original String.split + `Double.parseDouble` path with an indexOf-based city split and an ASCII double parser. That change significantly reduces short-lived String and boxed-number allocations and reduced elapsed times on sequential runs.

Footnote: Attempt-2 shows the optimized parsing in `sai` produced faster sequential times where available, but full comparison across all four permutations is limited by missing runs in this set.

## Attempt-3 (Divide-and-conquer batching)

The following runs were performed using a batch-based divide-and-conquer parallel processing mode (configured via `-DbatchSize`). The approach batches roughly N lines per worker thread and merges per-batch maps into a global ConcurrentHashMap.

| Mode | Parallel | Output file | Elapsed | Comparison |
|------|----------|-------------|---------|------------|
| baseline | False | baseline_output_sequential.txt | 00:03:35.3291708 | skipped |
| baseline | True | baseline_output_parallel.txt | 00:03:04.8014282 | skipped |
| sai | False | sai_output_sequential.txt | 00:02:40.7779279 | identical |
| sai | True | sai_output_parallel.txt | 00:02:17.9941156 | identical |

Notes/analysis:

- These numbers were produced with `-DbatchSize=500000` for the full measurements file — the large batch size gave a significant speed difference on this machine.
- Baseline parallel still outperformed Sai's parallel in some earlier experiments, but with `batchSize=500000` Sai shows a clear win in both sequential and parallel timings on the full file (sai is faster overall).
- The large batch size reduces task/merge overhead but increases per-task processing cost; try mid-range values (50k–500k) to find the sweet spot on your machine.

Footnote: Attempt-3's comparison rows show that `sai` outperforms `baseline` for both sequential and parallel runs when using large batch sizes — `sai` is the faster implementation in this attempt.

## Attempt-4 (Block-based reader & byte-level parsing)

This attempt focuses on reducing allocations and parsing overhead by reading the input file in large, owned byte[] blocks (~1 MB), aligning reads to newline boundaries, and parsing numeric fields directly from the byte arrays instead of creating many intermediate Strings. The goal is to hand each block to a worker thread that builds a local map of city statistics, then merge those local maps into the global result with a controlled merge phase.

Key points of the approach:

- File IO: read the file using a `FileChannel`/`RandomAccessFile` style loop into a freshly allocated `byte[]` for each block. After the block is filled the reader extends the block forward to the next `\n` to avoid splitting a measurement line across blocks.
- Owned block processing: each block is owned by a single task (worker thread). The task scans the block for `\n` and `;` separators, extracts the city name as a `String` (only for the textual city field), and parses the temperature directly from the `byte[]` using a custom `parseDoubleAscii(byte[], start, end)` routine. This avoids per-line `String` allocations for the numeric token and avoids boxing/unboxing allocations for temporary numbers.
- Per-block local aggregation: each task creates and updates a local `Map<String, CityTemperatureRecord>` while scanning its block. `CityTemperatureRecord` is a compact mutable accumulator (min, max, sum, count) to minimize object churn.
- Bounded concurrency: submission of block tasks is bounded using a `Semaphore` and a fixed `ExecutorService` (we use up to 16 worker threads for parallel mode) so the number of in-flight blocks is limited and memory pressure remains predictable.
- Merge phase: when a block task completes it returns its local map. The reader merges maps into the global result in a controlled manner (single-threaded merges or with brief synchronized updates) to keep the merge cost deterministic.

Why this helps:

- Minimizes short-lived String/Double allocations by parsing numbers from bytes and keeping the city string allocation to only one per unique city seen in the block.
- Reduces GC pressure and pause variability on large inputs.
- Bounded in-flight work avoids queuing the entire file as tasks which can otherwise spike memory.

Observed runs

The following table contains the run times you provided for Attempt-4 (block-based reader + byte-level parsing, where `sai` is the optimized implementation):

| Mode | Parallel | Output file | Elapsed | Comparison |
|------|----------|-------------|---------|------------|
| baseline | False | baseline_output_sequential.txt | 00:03:13.7876882 | skipped |
| baseline | True | baseline_output_parallel.txt | 00:02:52.9399301 | identical |
| sai | False | sai_output_sequential.txt | 00:01:59.1310447 | identical |
| sai | True | sai_output_parallel.txt | 00:00:32.4876591 | skipped |

Notes on these results:

- The block-based reader drastically reduced elapsed time for the parallel `sai` run on this machine (the `sai` parallel run is much faster than prior attempts). This matches expectations: fewer allocations + good parallelism + bounded concurrency.

Footnote: Attempt-4 demonstrates a dramatic improvement for the `sai` parallel run — `sai` (optimized implementation) is significantly faster than `baseline` in these measurements.
