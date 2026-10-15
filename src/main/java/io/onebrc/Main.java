package io.onebrc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.TreeMap;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class Main {
    private static final String FILE = "./data/measurements.txt";
    private static final int INF = 1 << 30;
    private static final byte SEMICOLON = ';';
    private static final byte DOT = '.';
    private static final byte MINUS = '-';

    public static void main(String[] args) throws IOException {
//        long start = System.currentTimeMillis();
        process();
//        System.out.println("Took: " + (System.currentTimeMillis() - start) + " ms");
    }

    private static void process() throws IOException {
        // Hashmap to store measurements
        StationTable stationTable = new StationTable();

        // Read line by line from file using mmap
        try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            MemorySegment file = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
            long offset = 0;
            byte[] nameBuffer = new byte[128]; // avoid create new String object every row

            while (offset < file.byteSize()) {
                // 1. Parse station name
                int nameLength = 0;
                int keyHash = 0;
                byte currentByte;

                while ((currentByte = file.get(JAVA_BYTE, offset++)) != SEMICOLON) {
                    nameBuffer[nameLength++] = currentByte;
                    keyHash = 31 * keyHash + currentByte; // internet reference
                }

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
                Entry currentEntry = stationTable.getOrInsert(nameBuffer, nameLength, keyHash);
                currentEntry.add(measurement);
            }
        }

        // Print result sorted by key
        TreeMap<String, String> results = new TreeMap<>();
        for (Entry entry : stationTable.entries) {
            if (entry == null) continue;
            String stationName = new String(entry.nameBuffer, StandardCharsets.UTF_8);
            results.put(stationName, entry.toString());
        }
        System.out.println(results);
    }

    private static int toDigit(byte b) {
        return b - '0';
    }

    public static class StationTable {
        private static final int SIZE = 1 << 14; // init with 2^14 entries
        private static final int MASK = SIZE - 1;
        final Entry[] entries = new Entry[SIZE];

        /**
         * hash ^ (hash >>> 16) to blend 16 MSB with 16 LSB, reduce collisions when & MASK.
         */
        public Entry getOrInsert(byte[] buffer, int length, int hash) {
            int index = (hash ^ (hash >>> 14)) & MASK; // internet reference

            while (true) {
                Entry entry = entries[index];

                if (entry == null) {
                    entries[index] = new Entry(Arrays.copyOf(buffer, length), hash);
                    return entries[index];
                }

                // check for collision (same index -> same hash, same bytes)
                if (
                        entry.hash == hash
                        && entry.nameBuffer.length == length
                        && Arrays.equals(entry.nameBuffer, 0, length, buffer, 0, length)
                ) {
                    return entry;
                }

                index = (index + 1) & MASK; // collision: try linear probing
            }
        }
    }

    public static class Entry {
        final byte[] nameBuffer;
        final int hash;
        long count;
        long sum;
        int min = INF;
        int max = -INF;

        public Entry(byte[] nameBuffer, int hash) {
            this.nameBuffer = nameBuffer;
            this.hash = hash;
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