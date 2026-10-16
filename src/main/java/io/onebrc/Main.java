package io.onebrc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class Main {
    private static final String FILE = "./data/measurements.txt";
    private static final int CHUNK_COUNT = Runtime.getRuntime().availableProcessors() * 16;

    public static void main(String[] args) throws IOException {
        process();
    }

    private static void process() throws IOException {
        // One table per chunk: threads never share a table
        StationTable[] stationTables = new StationTable[CHUNK_COUNT];

        // Read line by line from file using mmap
        try (FileChannel channel = FileChannel.open(Path.of(FILE), StandardOpenOption.READ)) {
            MemorySegment file = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), Arena.global());
            long fileSize = file.byteSize();

            // 1. Lines starting before tailStart are read without bound checks
            // tailStart = first line offset starts at fileSize - 128 (or 0 for files <= 128 bytes)
            long tailStart = Math.max(fileSize - 128, 0);
            while (tailStart > 0 && file.get(JAVA_BYTE, tailStart - 1) != '\n') tailStart++;

            // 2. Split [0, tailStart) into chunks, process each chunk simultaneously
            long[] boundaries = splitFileIntoChunks(file, tailStart);

            IntStream.range(0, CHUNK_COUNT)
                    .parallel()
                    .forEach(i -> {
                        long start = (i == 0) ? 0 : boundaries[i - 1] + 1;
                        stationTables[i] = new StationTable();
                        ChunkProcessor.processFileRange(file, start, boundaries[i] + 1, stationTables[i]);
                    });

            // 3. Process tail (<= 128 bytes) on a zero-padded copy, after all chunks are done
            long tailLength = fileSize - tailStart;
            MemorySegment tail = Arena.global().allocate(tailLength + 128); // zero-filled
            MemorySegment.copy(file, tailStart, tail, 0, tailLength); // copy tail lines to new segment
            ChunkProcessor.processFileRange(tail, 0, tailLength, stationTables[CHUNK_COUNT - 1]);
        }

        // 4. Merge chunk tables and print result sorted by key
        TreeMap<String, Entry> results = new TreeMap<>();
        for (StationTable stationTable : stationTables) {
            for (Entry entry : stationTable.entries) {
                if (entry == null) continue;
                String stationName = new String(entry.nameBuffer, StandardCharsets.UTF_8);
                results.merge(stationName, entry, Entry::merge);
            }
        }
        System.out.println(results);
    }

    /**
     * Split [0, end) into CHUNK_COUNT chunks. boundaries[i] = chunk[i]'s ending offset (a '\n')
     */
    private static long[] splitFileIntoChunks(MemorySegment file, long end) {
        long[] boundaries = new long[CHUNK_COUNT];
        long part = end / CHUNK_COUNT;

        boundaries[CHUNK_COUNT - 1] = end - 1;
        for (int i = CHUNK_COUNT - 2; i >= 0; --i) {
            boundaries[i] = boundaries[i + 1] - part;

            // move forward to the end of the line (-1 = nothing before the tail, small file)
            while (boundaries[i] >= 0 && file.get(JAVA_BYTE, boundaries[i]) != '\n') {
                boundaries[i]++;
            }
        }
        return boundaries;
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Hot loop: parses lines and updates the chunk's StationTable
     */
    static final class ChunkProcessor {
        private static final long SEMI_PATTERN = 0x3B3B3B3B3B3B3B3BL; // check ';' in 8 bytes
        private static final long ONES = 0x0101010101010101L;
        private static final long HIGH_BITS = 0x8080808080808080L;
        private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

        /**
         * Process every line that starts in [start, end)
         */
        static void processFileRange(MemorySegment file, long start, long end, StationTable stationTable) {
            long offset = start;
            long[] words = new long[1 << 4]; // 8 * 16 = 128 bytes

            while (offset < end) {
                // 1. Parse station name
                int wordsLength = 0;
                long nameStart = offset;
                long hash = 0, word, match;

                while (true) {
                    word = file.get(LONG_LE, offset); // read 8 bytes
                    match = findSemicolon(word); // check for ';'
                    if (match != 0) break; // ; found

                    words[wordsLength++] = word;
                    hash ^= word; // else accumulate hash
                    offset += 8;  // forward offset by 1 long
                }
                long nameBytes = getBytesBeforeSemicolon(word, match);
                words[wordsLength++] = nameBytes;
                words[wordsLength] = 0; // for 1-word station name

                int keyHash = Long.hashCode(addHash(hash, nameBytes));

                int semicolonIndex = getSemicolonIndex(match);
                offset += semicolonIndex; // forward to ';'
                int nameLength = (int) (offset - nameStart);
                offset++; // skip ';'

                // 2. Parse temperature
                long tempWord = file.get(LONG_LE, offset);
                int dotPos = dotPos(tempWord);
                int measurement = parseTemperature(tempWord, dotPos);
                offset += (dotPos >>> 3) + 3;  // skip '.', fraction digit, '\n'

                // 3. Update station stat
                Entry currentEntry = stationTable.getOrInsert(file, nameStart, nameLength, words, wordsLength, keyHash);
                currentEntry.add(measurement);
            }
        }

        /**
         * match = 0x0000008000000000L
         * (match ^ (match - 1) -> 0x0000007FFFFFFFFFL
         * (match ^ (match - 1) >>> 8 -> mask = 0x00000000FFFFFFFFL
         * Since little endian -> word & mask = station name part
         */
        private static long getBytesBeforeSemicolon(long word, long match) {
            return word & ((match ^ (match - 1)) >>> 8);
        }

        /**
         * Since little endian -> name station is LSB -> use trailing zeros to get index of ';'
         */
        private static int getSemicolonIndex(long match) {
            return Long.numberOfTrailingZeros(match) >>> 3; // 0...7
        }

        private static long addHash(long hash, long word) {
            return (hash ^ word) * 0x9E3779B97F4A7C15L; // internet reference
        }

        /**
         * for checking index of ';' in a long (8 bytes)
         * x = word ^ SEMI_PATTERN -> ';' byte = 00
         * hit = (x - 1) & ~x & HIGH_BITS
         *      -> (x - 1) & ~x keeps ';' = FF -> & HIGH_BITS keeps high bit in only ';' byte -> position of ';'
         */
        private static long findSemicolon(long word) {
            long input = word ^ SEMI_PATTERN;
            return (input - ONES) & ~input & HIGH_BITS;
        }

        private static final long MAGIC_MULTIPLIER = (100 * 0x1000000 + 10 * 0x10000 + 1);
        private static final long DOT_BITS = 0x10101000;
        // credit: merykitty
        // The 4th binary digit of the ascii of a digit is 1 while
        // that of the '.' is 0. This finds the decimal separator.
        // The value can be 12, 20, 28
        private static int dotPos(long word) {
            return Long.numberOfTrailingZeros(~word & DOT_BITS);
        }

        // credit: merykitty and royvanrijn
        private static int parseTemperature(long numberBytes, int dotPos) {
            // numberBytes contains the number: X.X, -X.X, XX.X or -XX.X
            final long invNumberBytes = ~numberBytes;

            // Calculates the sign
            final long signed = (invNumberBytes << 59) >> 63;
            final int _28MinusDotPos = (dotPos ^ 0b11100);
            final long minusFilter = ~(signed & 0xFF);
            // Use the pre-calculated decimal position to adjust the values
            final long digits = ((numberBytes & minusFilter) << _28MinusDotPos) & 0x0F000F0F00L;

            // Multiply by a magic (100 * 0x1000000 + 10 * 0x10000 + 1), to get the result
            final long absValue = ((digits * MAGIC_MULTIPLIER) >>> 32) & 0x3FF;
            // And apply the sign
            return (int) ((absValue + signed) ^ signed);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static class StationTable {
        private static final int SIZE = 1 << 14; // init with 2^14 entries
        private static final int MASK = SIZE - 1;
        final Entry[] entries = new Entry[SIZE];

        /**
         * hash ^ (hash >>> 16) to blend 16 MSB with 16 LSB, reduce collisions when & MASK.
         */
        public Entry getOrInsert(MemorySegment file, long nameStart, int length, long[] words, int wordLength, int hash) {
            int index = (hash ^ (hash >>> 14)) & MASK; // internet reference

            while (true) {
                Entry entry = entries[index];

                if (entry == null) { // new station (~400 times): copy name bytes + words only here
                    entries[index] = new Entry(
                            file.asSlice(nameStart, length).toArray(JAVA_BYTE),
                            Arrays.copyOf(words, wordLength),
                            hash,
                            words[0],
                            words[1]
                    );
                    return entries[index];
                }

                // check for collision (same index -> same hash, same words)
                // word count must match first: wordsEqual reads wordLength words from both arrays
                if (
                        entry.hash == hash
                        && entry.word0 == words[0]
                        && entry.word1 == words[1]
                        && (wordLength <= 2 || wordTailEquals(entry.words, words, wordLength))
                ) {
                    return entry;
                }

                index = (index + 1) & MASK; // collision: try linear probing
            }
        }

        private boolean wordTailEquals(long[] words, long[] words1, int wordLength) {
            // bound check
            if (words.length != wordLength) return false;

            for (int i = 2; i < wordLength; ++i)
                if (words[i] != words1[i]) return false;
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    public static class Entry {
        private static final int INF = 1 << 30;

        // station name
        final byte[] nameBuffer; // only used for printing, copied once when the station is first seen
        final long[] words;      // only used for comparing
        final long word0;
        final long word1;
        final int hash;

        // stats
        long count;
        long sum;
        int min = INF;
        int max = -INF;

        public Entry(byte[] nameBuffer, long[] words, int hash, long word0, long word1) {
            this.nameBuffer = nameBuffer;
            this.words = words;
            this.hash = hash;
            this.word0 = word0;
            this.word1 = word1;
        }

        public void add(int measurement) {
            count++;
            sum += measurement;
            min = Math.min(min, measurement);
            max = Math.max(max, measurement);
        }

        public Entry merge(Entry other) {
            count += other.count;
            sum += other.sum;
            min = Math.min(min, other.min);
            max = Math.max(max, other.max);
            return this;
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
