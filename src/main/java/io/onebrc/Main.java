package io.onebrc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class Main {
    private static final String FILE = "./data/measurements.txt";
    private static final int INF = 1 << 30;
    private static final byte SEMICOLON = ';';
    private static final byte DOT = '.';
    private static final byte MINUS = '-';

    public static void main(String[] args) throws IOException {
        long start = System.currentTimeMillis();
        process();
        System.out.println("Took: " + (System.currentTimeMillis() - start) + " ms");
    }

    private static void process() throws IOException {
        // Hashmap to store measurements
        Map<String, Stats> stationStats = new HashMap<>();

        // Read line by line from file using mmap
        try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            MemorySegment file = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
            byte[] buffer = new byte[128];
            long offset = 0;

            while (offset < file.byteSize()) {
                // 1. Parse station name
                int nameLength = 0;
                byte currentByte;
                while ((currentByte = file.get(JAVA_BYTE, offset++)) != SEMICOLON) {
                    buffer[nameLength++] = currentByte;
                }
                String station = new String(buffer, 0, nameLength, StandardCharsets.UTF_8);

                // 2. Parse temperature  ("-12.3" -> -123)
                int sign = 1;
                currentByte = file.get(JAVA_BYTE, offset++); // first char
                if (currentByte == MINUS) {
                    sign = -1;
                    currentByte = file.get(JAVA_BYTE, offset++);
                }
                int temp = toDigit(currentByte); // first digit
                currentByte = file.get(JAVA_BYTE, offset++);
                if (currentByte != DOT) {
                    temp = temp * 10 + toDigit(currentByte); // second digit
                    offset++; // skip '.'
                }
                currentByte = file.get(JAVA_BYTE, offset);
                temp = temp * 10 + toDigit(currentByte);
                offset += 2;   // skip decimal digit & \n
                int measurement = sign * temp;

                // 3. Update station stat
                stationStats.computeIfAbsent(station, k -> new Stats()).add(measurement);
            }
        }

        // Print result sorted by key
        System.out.println(new TreeMap<>(stationStats));
    }

    private static int toDigit(byte b) {
        return b - '0';
    }

    public static class Stats {
        private long count;
        private long sum;
        private int min = INF;
        private int max = -INF;

        public Stats() {
        }

        public void add(int measurement) {
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
            return min / 10.0 + "/" + round(sum / 10.0 / count) + "/" + max / 10.0;
        }
    }
}