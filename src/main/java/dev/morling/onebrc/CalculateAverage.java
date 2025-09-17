package dev.morling.onebrc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public class CalculateAverage {

    
    private static final char DELIMETER = ';';
    private static final char NEW_LINE = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char SPACE = ' ';

    // Use block-based processing for both sequential and parallel modes
    private static final String DEFAULT_BLOCK_SIZE = "1000000"; // 1 MB blocks

    public static void main(String[] args) throws IOException {
        final boolean asParallel = args.length > 0 && "parallel".equals(args[0]);
        // Allow overriding the measurements file via system property
        // -Dmeasurements=path
        String measurementsPath = System.getProperty("measurements", "measurements.txt");
        int blockSize = Integer.parseInt(System.getProperty("blockSize", DEFAULT_BLOCK_SIZE));
        if (blockSize < 1024)
            blockSize = 1024;
        
        System.out.println("Running in " + (asParallel ? "parallel" : "sequential") + " mode");
        System.out.println("Using block size: " + blockSize);
        System.out.println("Using measurements file: " + measurementsPath);
        System.out.println("End of configuration.");

        Path measurements = Path.of(measurementsPath);

        Map<String, CityTemperatureRecord> records = asParallel ? new ConcurrentHashMap<>() : new HashMap<>();

        int maxThreads = asParallel ? 16 : 1;
        processFileByBlocks(measurements, records, blockSize, maxThreads);

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        try (var recordStream = records.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())) {
            recordStream.forEach(e -> {
                CityTemperatureRecord acc = e.getValue();
                double min = acc.min;
                double max = acc.max;
                double sum = acc.sum;
                long count = acc.count;
                sb.append(acc.name + "=" +
                        String.format("%.1f", min) +
                        "/" +
                        String.format("%.1f", sum / count) +
                        "/" +
                        String.format("%.1f", max) + ", ");
            });
        }

        // Remove trailing comma and space
        if (records.size() > 0) {
            sb.setLength(sb.length() - 2);
        }
        sb.append("}");
        // write to file sai_output.txt
        System.out.println(sb.toString());
    }
   

    /**
     * Parse a block of bytes that contains whole lines (each line ends with
     * NEW_LINE).
     * The block is owned by the caller; this method will iterate lines without
     * creating Strings for each line. It produces a local Map<String,
     * CityTemperatureRecord>
     * equivalent to processBatch(List<String>), but works from a byte[] to avoid
     * allocations.
     */
    private static Map<String, CityTemperatureRecord> processBlock(byte[] block, long batchId) {
        Map<String, CityTemperatureRecord> local = new HashMap<>();
        int len = block.length;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            // find newline
            while (i < len && block[i] != NEW_LINE)
                i++;
            int lineEnd = (i < len && block[i] == NEW_LINE) ? i : i; // exclusive

            // Trim possible trailing CR
            int logicalEnd = lineEnd;
            if (logicalEnd > lineStart && block[logicalEnd - 1] == CARRIAGE_RETURN)
                logicalEnd--;

            // process bytes [lineStart, logicalEnd)
            if (logicalEnd - lineStart > 0) {
                // find delimiter ';'
                int delim = -1;
                for (int j = lineStart; j < logicalEnd; j++) {
                    if (block[j] == DELIMETER) {
                        delim = j;
                        break;
                    }
                }
                if (delim > lineStart && delim < logicalEnd - 1) {
                    // extract city name bytes and trim spaces
                    int cStart = lineStart;
                    int cEnd = delim;
                    // trim leading
                    while (cStart < cEnd && (block[cStart] == SPACE || block[cStart] == CARRIAGE_RETURN))
                        cStart++;
                    // trim trailing
                    while (cEnd > cStart && (block[cEnd - 1] == SPACE || block[cEnd - 1] == CARRIAGE_RETURN))
                        cEnd--;

                    String city = new String(block, cStart, cEnd - cStart, StandardCharsets.UTF_8);
                    try {
                        double temperature = Utils.parseDoubleAscii(block, delim + 1, logicalEnd);
                        CityTemperatureRecord acc = local.computeIfAbsent(city, (k) -> new CityTemperatureRecord(k));
                        acc.accept(temperature);
                    } catch (NumberFormatException ex) {
                        // ignore malformed
                    }
                }
            }

            // skip newline
            if (i < len && block[i] == NEW_LINE)
                i++;
        }

        // optional metrics recording moved to caller (processBatchWithMetrics used
        // previously)
        return local;
    }

    record BlockData(long position, byte[] data) {
    }

    /**
     * Example: block-based file processing using processBlock(byte[], long).
     * Reads ~blockSize bytes, aligns to next newline, and submits to a thread pool
     * (parallel) or processes inline (sequential).
     * Does NOT remove or affect processLine(String) or line-based code paths.
     *
     * To use: call from main() instead of processInParallel or Files.lines, e.g.:
     * processFileByBlocks(measurements, records, 1_000_000, asParallel ? 16 : 1);
     */
    private static void processFileByBlocks(Path file, Map<String, CityTemperatureRecord> records, int blockSize,
            int maxThreads) throws IOException {
        var executor = (maxThreads > 1)
                ? Executors.newFixedThreadPool(maxThreads)
                : null;
        List<Future<Map<String, CityTemperatureRecord>>> futures = new ArrayList<>();
        Semaphore permits = (maxThreads > 1) ? new Semaphore(maxThreads)
                : null;
        AtomicLong batchId = new AtomicLong(0);

        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            long fileSize = ch.size();
            long position = 0L;
            while (position < fileSize) {
                int toRead = (int) Math.min(blockSize, fileSize - position);
                byte[] buf = new byte[toRead];
                int read = readUptoBlockSize(ch, position, buf);
                if (read <= 0)
                    break;
                position += read;

                BlockData blockData = getBlockData(ch, position, fileSize, read, buf);

                final byte[] block = blockData.data();
                position = blockData.position();

                final long id = batchId.incrementAndGet();
                if (executor != null) {
                    parallelProcessing(executor, futures, permits, block, id);
                } else {
                    sequentialProcessing(records, block, id);
                }
            }
            if (executor != null) {
                // merge all results
                mergeResults(records, futures);
            }
        } finally {
            if (executor != null)
                executor.shutdown();
        }
    }

    private static int readUptoBlockSize(FileChannel ch, long position, byte[] buf) throws IOException {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf);
        int read = 0;
        while (bb.hasRemaining()) {
            int r = ch.read(bb, position + read);
            if (r <= 0)
                break;
            read += r;
        }
        return read;
    }

    private static BlockData getBlockData(FileChannel ch, long position, long fileSize, int read, byte[] buf)
            throws IOException {
        // extend to next newline if not at EOF
        if (position < fileSize && buf[read - 1] != (byte) NEW_LINE) {
            ByteArrayOutputStream extra = new ByteArrayOutputStream(8192);
            byte[] tmp = new byte[8192];
            boolean found = false;
            while (!found && position < fileSize) {
                int r = ch.read(ByteBuffer.wrap(tmp), position);
                if (r <= 0)
                    break;
                for (int i = 0; i < r; i++) {
                    extra.write(tmp[i]);
                    if (tmp[i] == (byte) NEW_LINE) {
                        found = true;
                        position += i + 1;
                        break;
                    }
                }
                if (!found)
                    position += r;
            }
            byte[] ext = extra.toByteArray();
            byte[] block = new byte[read + ext.length];
            System.arraycopy(buf, 0, block, 0, read);
            System.arraycopy(ext, 0, block, read, ext.length);
            buf = block;
        } else if (read < buf.length) {
            // shrink to actual read size
            byte[] block = new byte[read];
            System.arraycopy(buf, 0, block, 0, read);
            buf = block;
        }
        return new BlockData(position, buf);
    }

    private static void parallelProcessing(ExecutorService executor,
            List<Future<Map<String, CityTemperatureRecord>>> futures,
            Semaphore permits,
            final byte[] block, final long id) throws IOException {
        try {
            permits.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(ie);
        }
        futures.add(executor.submit(() -> {
            try {
                return processBlock(block, id);
            } finally {
                permits.release();
            }
        }));
    }

    private static void sequentialProcessing(Map<String, CityTemperatureRecord> records, final byte[] block,
            final long id) {
        Map<String, CityTemperatureRecord> local = processBlock(block, id);
        // merge immediately
        for (var e : local.entrySet()) {
            mergeRecords(records, e);
        }
    }

    private static void mergeResults(Map<String, CityTemperatureRecord> records,
            List<Future<Map<String, CityTemperatureRecord>>> futures) {
        for (var f : futures) {
            try {
                if (f == null)
                    continue;
                var local = f.get();
                if (local == null)
                    continue;
                // merge local map into global ConcurrentHashMap
                for (var e : local.entrySet()) {
                    if (e == null || e.getKey() == null || e.getValue() == null)
                        continue;
                    mergeRecords(records, e);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while merging results", ie);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private static void mergeRecords(Map<String, CityTemperatureRecord> records,
            Entry<String, CityTemperatureRecord> e) {
        records.merge(e.getKey(), e.getValue(), (existing, incoming) -> {
            if (existing == null)
                return incoming;
            if (existing.count == 0) {
                existing.min = incoming.min;
                existing.max = incoming.max;
                existing.sum = incoming.sum;
                existing.count = incoming.count;
            } else if (incoming.count > 0) {
                if (incoming.min < existing.min)
                    existing.min = incoming.min;
                if (incoming.max > existing.max)
                    existing.max = incoming.max;
                existing.sum += incoming.sum;
                existing.count += incoming.count;
            }
            return existing;
        });
    }

}
