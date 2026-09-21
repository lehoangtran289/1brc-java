# 1BRC (One Billion Row Challenge) - Java

My solution to the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc), which reads a file of weather station measurements (`<station>;<temperature>` per line) and computes the min/mean/max temperature per station as fast as possible.

## Requirements

- Java 21 (the project targets `--release 21`, see `pom.xml`)
- Maven (or use the bundled `./mvnw` wrapper)
- (Optional) `hyperfine` for evaluation

## 1. Compile

```bash
mvn clean package
```

This builds `target/1brc-java-1.0-SNAPSHOT.jar`, which all the scripts below invoke via
`java --class-path target/1brc-java-1.0-SNAPSHOT.jar <main class>`.

## 2. Generate a measurements file

There's no data file checked into the repo - generate one first with `create_measurements_fast.sh`:

```bash
./create_measurements_fast.sh 1000000000   # 1 billion rows, full benchmark size
./create_measurements_fast.sh 10000        # small file for quick local testing
```

This writes `data/measurements.txt` (always overwriting whatever was there before - rename/move the file if you  want to keep more than one around, e.g. `mv data/measurements.txt data/measurements_1b.txt`).

## 3. Run

```bash
./calculate_average.sh
```

Runs `io.onebrc.Main`, which reads `data/measurements.txt` and prints the aggregated result.

## 4. Test correctness

```bash
./test.sh
```

Runs `calculate_average.sh` against every sample in `src/test/resources/samples/*.txt` and diffs the output  against the expected `*.out` file for each. Pass a specific file (or glob) to test a subset:

```bash
./test.sh src/test/resources/samples/measurements-1.txt
./test.sh 'src/test/resources/samples/measurements-*.txt'
```

## 5. Benchmark

```bash
./evaluate.sh
```

Runs `calculate_average.sh` under [hyperfine](https://github.com/sharkdp/hyperfine) to measure wall-clock time  against whatever is currently in `data/measurements.txt` - generate a full-size file first (step 2) for a meaningful benchmark.

## Reference

Scripts and test samples in this repo are adapted from the original [gunnarmorling/1brc](https://github.com/gunnarmorling/1brc) repository - see there for the full challenge rules, leaderboard, and other contributors' solutions.
