# Memory Benchmarking

Measure and visualize JVM memory usage comparing the naive (buffer-all) approach against the 5 streaming aggregation variants.

## Quick Start

```bash
# Build and run benchmarks (requires Docker)
mvn package -DskipTests
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
java -cp target/classes:target/dependency/* com.example.benchmark.BenchmarkOrchestrator

# Custom parameters
java -cp target/classes:target/dependency/* com.example.benchmark.BenchmarkOrchestrator \
  --orders=500000 --cpus=4 --interval=50
```

## Architecture

The benchmark runs **server and each variant in separate Docker containers** for accurate memory measurement. This ensures:
- Each variant runs in complete isolation
- Memory measurements are per-variant (no cumulative effects)
- CPU resources can be controlled per container

```
┌─────────────────────────────────────────────────────────────────┐
│            BenchmarkOrchestrator (host JVM)                     │
│  - Manages Docker containers                                    │
│  - Starts server container                                      │
│  - Spawns variant containers (one per variant)                  │
│  - Collects JSON results                                        │
│  - Renders combined ASCII table                                 │
└───────────────────────────┬─────────────────────────────────────┘
                            │ docker run
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│ Server        │   │ Variant       │   │ Variant       │
│ Container     │   │ Container     │   │ Container     │
│ (port 8080)   │   │ --variant=    │   │ --variant=    │
│               │   │   naive       │   │   stream      │
│ --cpus=N      │   │ --cpus=N      │   │ --cpus=N      │
└───────────────┘   └───────────────┘   └───────────────┘
        │                   │                   │
        └───────────────────┴───────────────────┘
                    Docker network
```

## Why Docker Isolation?

In a single JVM process, RSS (Resident Set Size) is cumulative:
- JVM doesn't return memory pages to OS after GC
- Each variant adds to RSS (thread stacks, JIT code, off-heap buffers)
- Later variants appear to use more RSS simply because they run later

With Docker isolation:
- Each variant gets a fresh JVM
- Memory measurements are accurate per-variant
- CPU resources are controlled and consistent

## What It Measures

### JVM Metrics (via MemoryMXBean)

| Metric | Description |
|--------|-------------|
| Peak Heap | Maximum heap memory used during run |
| Allocation Rate | MB/s allocated (estimated from heap growth) |
| GC Count | Number of GC events detected (heap drops > 10%) |

### Derived Metrics

| Metric | Description |
|--------|-------------|
| Duration | Total execution time |
| Memory Efficiency | How well the variant manages memory |

## Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--orders=N` | 100,000 | Total number of orders to process |
| `--interval=N` | 100 | Sample interval in milliseconds |
| `--cpus=N` | 2 | CPU cores per container |
| `--pageSize=N` | 10,100,1000 | Page size (single value runs only that size) |

## Output Format

The benchmark produces ASCII summary tables:

```
═══════════════════════════════════════════════════════════════════
  MEMORY BENCHMARK (Docker Isolation)
  Orders: 100,000
  Page sizes: 10, 100, 1000
  CPUs per container: 2
  Variants: Naive, Stream API, Iterator, Async, Reactor, Virtual Threads
═══════════════════════════════════════════════════════════════════

▶ Page size: 100
────────────────────────────────────────────────────────────────────
  Starting server container...
  Waiting for server to start... ready
  Running naive... done (2.50s)
  Running stream... done (2.34s)
  Running iterator... done (2.21s)
  Running async... done (2.45s)
  Running reactor... done (2.38s)
  Running virtual... done (2.15s)

  ┌─────────────────┬──────────┬───────────┬───────────┬────────────┐
  │ Variant         │ Duration │ Peak Heap │ Alloc Rate│ Est. GCs   │
  ├─────────────────┼──────────┼───────────┼───────────┼────────────┤
  │ Naive           │   2.50s  │   180 MB  │    45 MB/s│      5     │
  │ Stream API      │   2.34s  │    48 MB  │    21 MB/s│      3     │
  │ Iterator        │   2.21s  │    45 MB  │    22 MB/s│      3     │
  │ Async           │   2.45s  │    52 MB  │    20 MB/s│      4     │
  │ Reactor         │   2.38s  │    55 MB  │    23 MB/s│      4     │
  │ Virtual Threads │   2.15s  │    46 MB  │    22 MB/s│      3     │
  └─────────────────┴──────────┴───────────┴───────────┴────────────┘
```

## Understanding the Results

### Memory Patterns

**Naive (buffer-all) approach:**
- Memory grows linearly as pages are fetched
- Peak heap is O(total_orders) - proportional to dataset size
- Memory is the same regardless of page size

**Streaming approaches (all 5 variants):**
- Memory stays bounded as pages are processed
- Peak heap is O(page_size) - proportional to page size only
- All 5 variants show similar, much lower peak memory

### Expected Results

With 100,000 orders:
- **Naive**: ~150-200 MB peak heap (stores all orders)
- **Streaming variants**: ~10-50 MB peak heap (stores one page)

Key insight: The naive approach uses 3-20x more memory than streaming approaches.

## Manual Testing

### Build Docker Images

```bash
mvn package -DskipTests
docker build -t streaming-benchmark-server -f Dockerfile.server .
docker build -t streaming-benchmark-client -f Dockerfile.benchmark .
```

### Run Single Variant Manually

```bash
# Create network
docker network create test-net

# Start server
docker run -d --name server --network test-net \
  -e ORDERS=10000 -e PAGE_SIZE=100 \
  streaming-benchmark-server

# Wait for server to start
sleep 3

# Run a single variant
docker run --rm --network test-net \
  -e VARIANT=naive \
  -e SERVER_URL=http://server:8080/orders \
  streaming-benchmark-client

# Cleanup
docker stop server && docker rm server
docker network rm test-net
```

### Run Single Variant Without Docker

```bash
# Terminal 1: Start server
java -Xms64m -Xmx128m -cp target/classes:target/dependency/* \
  com.example.streaming.server.SimpleOrdersServerMain 8080 10000 100

# Terminal 2: Run benchmark
java -Xms256m -Xmx512m -XX:+UseG1GC -cp target/classes:target/dependency/* \
  com.example.benchmark.MemoryBenchmarkMain \
  --url=http://localhost:8080/orders \
  --variant=naive \
  --interval=100
```

## Key Classes

| Class | Purpose |
|-------|---------|
| `BenchmarkOrchestrator` | Docker-based orchestrator, runs full benchmark suite |
| `MemoryBenchmarkMain` | Single-variant benchmark runner, outputs JSON |
| `SimpleOrdersServerMain` | Standalone server entry point |
| `BenchmarkRunner` | Runs single variant with memory sampling |
| `MemoryCollector` | Collects JVM memory metrics |
| `MemoryMetrics` | Data record for single measurement |
| `BenchmarkResult` | Holds all samples and derived metrics |
| `NaiveAggregator` | The anti-pattern: buffers all orders in memory |

## JVM Options

**Server container:**
```
-Xms64m -Xmx128m    # Small heap for server
```

**Benchmark container:**
```
-Xms256m -Xmx512m    # Fixed heap size for benchmark
-XX:+UseG1GC         # G1 garbage collector
```

## Extending the Benchmark

To add a new variant:

1. Create your aggregator class implementing the same interface
2. Add case to `MemoryBenchmarkMain.runVariantOnce()`:
   ```java
   case "myvariant" -> new MyVariantAggregator(url).aggregateOrders();
   ```
3. Add display name in `getVariantDisplayName()`
4. Add to `VARIANTS` array in `BenchmarkOrchestrator`
