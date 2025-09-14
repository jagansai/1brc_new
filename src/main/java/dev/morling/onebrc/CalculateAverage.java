package dev.morling.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class CalculateAverage {

    /**
     * Mutable accumulator used per-city to reduce allocations and support
     * concurrent updates.
     */
    private static final class CityTemperatureRecord {
        final String name;
        double min;
        double max;
        double sum;
        long count;

        CityTemperatureRecord(String name) {
            this.name = name;
            this.min = Double.POSITIVE_INFINITY;
            this.max = Double.NEGATIVE_INFINITY;
            this.sum = 0.0;
            this.count = 0;
        }

        // CityTemperatureRecord(String name, double value) {
        // this.name = name;
        // this.min = value;
        // this.max = value;
        // this.sum = value;
        // this.count = 1;
        // }

        void accept(double v) {
            if (count == 0) {
                min = v;
                max = v;
                sum = v;
                count = 1;
            } else {
                if (v < min)
                    min = v;
                if (v > max)
                    max = v;
                sum += v;
                count++;
            }
        }
    }

    private static final String DELIMETER = ";";

    public static void main(String[] args) throws IOException {
        final boolean asParallel = args.length > 0 && "parallel".equals(args[0]);
        // Allow overriding the measurements file via system property
        // -Dmeasurements=path
        String measurementsPath = System.getProperty("measurements", "measurements.txt");
        System.out.println("Using measurements file: " + measurementsPath);
        Path measurements = Path.of(measurementsPath);

        // Use one small CityTemperatureRecord instance per city; choose
        // ConcurrentHashMap only for parallel
        Map<String, CityTemperatureRecord> records = asParallel ? new ConcurrentHashMap<>() : new HashMap<>();

        try (var stream = Files.lines(measurements)) {
            var lineStream = asParallel ? stream.parallel() : stream;
            lineStream.forEach((line) -> processLine(line, records, asParallel));
        } finally {
            // System.out.println("Finished processing lines.");
        }

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

    private static void processLine(String line, Map<String, CityTemperatureRecord> records, boolean asParallel) {
        try {
            String[] parts = line.split(DELIMETER);
            if (parts.length == 2) {
                String city = parts[0].trim();
                double temperature = Double.parseDouble(parts[1].trim());
                CityTemperatureRecord cityRecord = records.computeIfAbsent(city,
                        (k) -> new CityTemperatureRecord(k));
                if (asParallel) {
                    synchronized (cityRecord) {
                        cityRecord.accept(temperature);
                    }
                } else {
                    // sequential
                    cityRecord.accept(temperature);
                }

            }
        } catch (NumberFormatException e) {
            // Log and ignore malformed lines
            System.err.println("Ignoring malformed line: " + line);
        }
    }
}
