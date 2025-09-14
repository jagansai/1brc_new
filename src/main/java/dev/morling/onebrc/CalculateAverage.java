package dev.morling.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CalculateAverage {

    /**
     * Mutable accumulator used per-city to reduce allocations and support concurrent updates.
     */
    private static final class CityTemperatureRecord {
        final String name;
        double min;
        double max;
        double sum;
        long count;

        CityTemperatureRecord(String name, double value) {
            this.name = name;
            this.min = value;
            this.max = value;
            this.sum = value;
            this.count = 1;
        }

        synchronized void accept(double v) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
            count++;
        }
    }

    private static final String DELIMETER = ";";

    public static void main(String[] args) throws IOException {
        final boolean asParallel = args.length > 0 && "parallel".equals(args[0]);
        Path measurements = Path.of("measurements.txt");

    // Use one small CityTemperatureRecord instance per city; ConcurrentHashMap for thread-safety
    Map<String, CityTemperatureRecord> records = new ConcurrentHashMap<>();

        try (var stream = Files.lines(measurements)) {
            var lineStream = asParallel ? stream.parallel() : stream;
            lineStream.forEach((line) -> processLine(line, records));
        } finally {
            // System.out.println("Finished processing lines.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        try ( var recordStream = records.entrySet().stream()
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

    private static void processLine(String line, Map<String, CityTemperatureRecord> records) {
        try {
            String[] parts = line.split(DELIMETER);
            if (parts.length == 2) {
                String city = parts[0].trim();
                double temperature = Double.parseDouble(parts[1].trim());

                // Use compute to initialize or update the Stats atomically per key.
                records.compute(city, (_, acc) -> {
                    if (acc == null) {
                        return new CityTemperatureRecord(city, temperature);
                    } else {
                        acc.accept(temperature);
                        return acc;
                    }
                });
            }
        } catch (NumberFormatException e) {
            // Log and ignore malformed lines
            System.err.println("Ignoring malformed line: " + line);
        }
    }
}
