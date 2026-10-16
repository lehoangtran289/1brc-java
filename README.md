# 1BRC (One Billion Row Challenge) - Java

My solution to the [One Billion Row Challenge](https://github.com/gunnarmorling/1brc), which reads a file of weather station stats (`<station>;<temperature>` per line) and computes the min/mean/max temperature per station as fast as possible.

**Result:**

- 100M rows (1.4 GB): 0.5s
- 1B rows (14 GB): 13.5s
- Setup: Apple M1 Pro (8 cores: 6 performance + 2 efficiency, 16 GB RAM), OpenJDK 21 (Temurin)
- Write-up: [writeup/writeup.md](writeup/writeup.md), the pass-by-pass optimization log
- Solution: [Main.java](src/main/java/io/onebrc/Main.java)

## Data

The text file contains temperature values for a range of weather stations. Each row is one measurement in the format `<string: station name>;<double: measurement>`, with the measurement value having exactly one fractional digit. The following shows ten rows as an example:

```
Hamburg;12.0
Bulawayo;8.9
Palembang;38.8
St. John's;15.2
Cracow;12.6
Bridgetown;26.9
Istanbul;6.2
Roseau;34.4
Conakry;31.2
Istanbul;23.0
```

The task is to write a Java program which reads the file, calculates the min, mean, and max temperature value per weather station, and emits the results on stdout like this (i.e. sorted alphabetically by station name, and the result values per station in the format `<min>/<mean>/<max>`, rounded to one fractional digit):

```
{Abha=-23.0/18.0/59.2, Abidjan=-16.2/26.0/67.3, Abéché=-10.0/29.4/69.0, Accra=-10.1/26.4/66.4, Addis Ababa=-23.7/16.0/67.0, Adelaide=-27.8/17.3/58.5, ...}
```

## Requirements

- Java 21 (the project targets `--release 21`, see `pom.xml`)
- Maven (or use the bundled `./mvnw` wrapper)
- (Optional) `hyperfine` for evaluation

## 0. TL;DR Set up

```bash
# build (re-run after every code change)
mvn clean package

# generate data files once, each under its own name (the generator always writes data/measurements.txt)
# You can use the fast version
./create_measurements.sh 1000000    && mv data/measurements.txt data/measurements_1M.txt
./create_measurements.sh 1000000000 && mv data/measurements.txt data/measurements_1b.txt

# test correctness against the samples in src/test/resources/samples using
./test.sh

# To benchmark: point data/measurements.txt at the file you want, then run either 1 of these "ln -sf" command, and then run evaluate.sh
ln -sf measurements_1M.txt data/measurements.txt 
ln -sf measurements_1b.txt data/measurements.txt   # for full benchmark

./evaluate.sh

# OR you can run, pay attention to data file name
./evaluate_1b.sh
```

- Check which file is active with `ls -l data/measurements.txt`.

## 1. Compile

```bash
mvn clean package
```

This builds `target/1brc-java-1.0-SNAPSHOT.jar`, which all the scripts below need to invoke via
`java --class-path target/1brc-java-1.0-SNAPSHOT.jar <main class>`.

## 2. Generate a stats file

There's no data file checked into the repo - generate one first with `create_measurements_fast.sh`:

```bash
./create_measurements_fast.sh 1000000000   # 1 billion rows, full benchmark size
./create_measurements_fast.sh 10000        # small file for quick local testing
```

This writes `data/stats.txt` (always overwriting whatever was there before - rename/move the file if you  want to keep more than one around, e.g. `mv data/stats.txt data/measurements_1b.txt`).

## 3. Run

```bash
./calculate_average.sh
```

Runs `io.onebrc.Main`, which reads `data/stats.txt` and prints the aggregated result.

## 4. Test correctness

```bash
./test.sh
```

This script runs `calculate_average.sh` against every sample in `src/test/resources/samples/*.txt` and diffs the output  against the expected `*.out` file for each. Pass a specific file (or glob) to test a subset:

```bash
./test.sh src/test/resources/samples/stats-1.txt
./test.sh 'src/test/resources/samples/stats-*.txt'
```

## 5. Benchmark

```bash
./evaluate.sh
```

Runs `calculate_average.sh` under [hyperfine](https://github.com/sharkdp/hyperfine) to measure wall-clock time  against whatever is currently in `data/stats.txt` - generate a full-size file first (step 2) for a meaningful benchmark.

## Reference

Scripts and test samples in this repo are adapted from the original [gunnarmorling/1brc](https://github.com/gunnarmorling/1brc) repository - see there for the full challenge rules, leaderboard, and other contributors' solutions.
