package io.onebrc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
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
        Map<Key, Stats> stationStats = new HashMap<>();

        // Read line by line from file using mmap
        try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            MemorySegment file = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
            byte[] buffer = new byte[128];
            long offset = 0;
            Key tempKey = new Key(buffer, 0, 0); // avoid create new String object every row

            while (offset < file.byteSize()) {
                // 1. Parse station name
                int nameLength = 0;
                int keyHash = 0;
                byte currentByte;

                while ((currentByte = file.get(JAVA_BYTE, offset++)) != SEMICOLON) {
                    buffer[nameLength++] = currentByte;
                    keyHash = 31 * keyHash + currentByte; // internet reference
                }
                tempKey.length = nameLength;
                tempKey.hash = keyHash;

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
                Stats currentStats = stationStats.get(tempKey);
                if (currentStats == null) {
                    currentStats = new Stats();
                    Key stationKey = new Key(Arrays.copyOf(buffer, nameLength), nameLength, keyHash);
                    stationStats.put(stationKey, currentStats);
                }
                currentStats.add(measurement);
            }
        }

        // Print result sorted by key
        System.out.println(new TreeMap<>(stationStats));
    }

    private static int toDigit(byte b) {
        return b - '0';
    }

    public static class Key implements Comparable<Key> {
        private final byte[] bytes;
        private int length;
        private int hash;

        public Key(byte[] bytes, int length, int hash) {
            this.bytes = bytes;
            this.length = length;
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Key other)) return false;
            return Arrays.equals(bytes, 0, length, other.bytes, 0, other.length);
        }

        @Override
        public int compareTo(Key o) {
            return Arrays.compareUnsigned(bytes, 0, length, o.bytes, 0, o.length);
        }

        @Override
        public String toString() {
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }
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