package io.onebrc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public class Main {
    private static final String FILE = "./data/measurements.txt";

    public static void main(String[] args) throws IOException {
        // build hashmap to store measurements
        Map<String, Stats> stats = new HashMap<>();

        try (Stream<String> lines = Files.lines(Path.of(FILE))) {
            lines.forEach(line -> {
                String[] parts = line.split(";");
                String station = parts[0];
                double measurement = Double.parseDouble(parts[1]);

                stats.computeIfAbsent(station, k -> new Stats()).add(measurement);
            });
        }

        // build result
        Map<String, Stats> resultStats = new TreeMap<>(stats);
        System.out.println(resultStats);
    }

    public static class Stats {
        private long count;
        private double sum;
        private double min = Double.POSITIVE_INFINITY;
        private double max = Double.NEGATIVE_INFINITY;

        public Stats() {
        }

        public void add(double measurement) {
            count++;
            sum += measurement;
            min = Math.min(min, measurement);
            max = Math.max(max, measurement);
        }

        private static double round(double value) {
            return Math.round(value * 10.0) / 10.0;
        }

        @Override
        public String toString() {
            return round(min) + "/" + round(round(sum) / count) + "/" + round(max);
        }
    }
}