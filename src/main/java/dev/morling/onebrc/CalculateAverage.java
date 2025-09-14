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
            int delimIdx = line.indexOf(DELIMETER);
            if (delimIdx > 0 && delimIdx < line.length() - 1) {
                String city = line.substring(0, delimIdx).trim();
                double temperature = parseDoubleAscii(line, delimIdx + 1, line.length());
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

    /**
     * Fast ASCII double parser for the substring [start, end).
     * Supports optional leading/trailing spaces, sign, fractional part and
     * exponent.
     * Throws NumberFormatException on invalid input.
     */
    private static double parseDoubleAscii(String s, int start, int end) {
        int i = start;
        // skip leading whitespace
        while (i < end && Character.isWhitespace(s.charAt(i)))
            i++;
        if (i >= end)
            throw new NumberFormatException(s.substring(start, end));

        int sign = 1;
        char c = s.charAt(i);
        if (c == '+' || c == '-') {
            if (c == '-')
                sign = -1;
            i++;
        }

        long intPart = 0;
        while (i < end) {
            c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                intPart = intPart * 10 + (c - '0');
                i++;
            } else
                break;
        }

        double value = (double) intPart;

        // fraction
        if (i < end && s.charAt(i) == '.') {
            i++;
            long frac = 0;
            int fracLen = 0;
            while (i < end) {
                c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    frac = frac * 10 + (c - '0');
                    fracLen++;
                    i++;
                } else
                    break;
            }
            if (fracLen > 0) {
                value += frac / Math.pow(10.0, fracLen);
            }
        }

        // exponent
        if (i < end && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            int expSign = 1;
            if (i < end) {
                c = s.charAt(i);
                if (c == '+' || c == '-') {
                    if (c == '-')
                        expSign = -1;
                    i++;
                }
            }
            int exp = 0;
            int expDigits = 0;
            while (i < end) {
                c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    exp = exp * 10 + (c - '0');
                    expDigits++;
                    i++;
                } else
                    break;
            }
            if (expDigits > 0) {
                value = value * Math.pow(10.0, expSign * exp);
            } else {
                throw new NumberFormatException(s.substring(start, end));
            }
        }

        // skip trailing whitespace
        while (i < end && Character.isWhitespace(s.charAt(i)))
            i++;
        if (i != end) {
            // trailing garbage
            throw new NumberFormatException(s.substring(start, end));
        }

        return sign * value;
    }
}
