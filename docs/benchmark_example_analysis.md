# Benchmark Example Analysis

This document analyzes a specific benchmark run to explain the observed results and what they reveal about each aggregation variant's characteristics.

## Test Conditions

| Parameter | Value |
|-----------|-------|
| Total Orders | 500,000 |
| Page Size | 1,000 |
| Sample Interval | 100ms |
| CPUs per Container | 1 |
| JVM Heap | 256-512 MB (G1GC) |
| Docker Isolation | Yes (separate container per variant) |

**Command used:**
```bash
./benchmark.sh --orders=500000 --pageSize=1000 --cpus=1
```

## Benchmark Results

```
┌─────────────────┬──────────┬───────────┬──────────┬───────────┬────────────┐
│ Variant         │ Duration │ Peak Heap │ Avg Heap │ Alloc Rate│ Est. GCs   │
├─────────────────┼──────────┼───────────┼──────────┼───────────┼────────────┤
│ Naive           │   0.99s  │    113 MB │    60 MB │   246 MB/s│      3     │
│ Stream API      │   1.00s  │    140 MB │    64 MB │   229 MB/s│      1     │
│ Iterator        │   0.93s  │    135 MB │    60 MB │   228 MB/s│      1     │
│ Pooled Iterator │   0.97s  │    110 MB │    63 MB │   104 MB/s│      0     │
│ Async           │   1.30s  │    140 MB │    74 MB │   390 MB/s│      3     │
│ Reactor         │   0.91s  │    137 MB │    70 MB │   249 MB/s│      1     │
│ Virtual Threads │   0.88s  │    144 MB │    72 MB │   243 MB/s│      1     │
└─────────────────┴──────────┴───────────┴──────────┴───────────┴────────────┘
```

## Memory Usage Over Time

These graphs show heap usage sampled at 100ms intervals. All graphs share the same time axis (0–1.3s) so you can visually compare how long each variant runs and how its memory evolves.

```
Naive - Heap Used (MB)
  123 |                                      :
      |                          ....... ....#
   62 |               ####    ...#######.#####
      |           ....####....################
      |   #####...############################
    0 |...####################################
      +--------------------------------------------------
      0                                              1.3s

Stream API - Heap Used (MB)
  153 |                   ::::
      |               ::::####
   77 |           ############               :
      |       ::::############           ::::#
      |   ::::################   ::::#########
    0 |...####################:::#############
      +--------------------------------------------------
      0                                              1.3s

Iterator - Heap Used (MB)
  148 |                   ::::
      |               ::::####
   74 |           ::::########            :
      |       ....############           ##
      |   ....################   ....######
    0 |...####################:::##########
      +--------------------------------------------------
      0                                              1.3s

Pooled Iterator - Heap Used (MB)
  120 |                                  ...:
      |                          ....########
   60 |                   ....###############
      |             ..#######################
      |   ....###############################
    0 |...###################################
      +--------------------------------------------------
      0                                              1.3s

Async - Heap Used (MB)
  154 |               ::::                       :::::
      |               ####       ######          #####
   77 |           ########    ...######      #########  .
      |       ############    #########  ....#########  #
      |   ################....#########  #############..#
    0 |...###############################################
      +--------------------------------------------------
      0                                              1.3s

Reactor - Heap Used (MB)
  150 |                   ::::
      |               ::::####           ..
   75 |           ::::########           ##
      |       ################       ######
      |   ####################   ::::######
    0 |...####################:::##########
      +--------------------------------------------------
      0                                              1.3s

Virtual Threads - Heap Used (MB)
  158 |                   ::::
      |               ::::####
   79 |           ::::########          :
      |       ::::############   ....####
      |   ####################...########
    0 |...###############################
      +--------------------------------------------------
      0                                              1.3s
```

**What the graphs reveal:**

- **Naive**: Sawtooth pattern with 3 visible GC drops — heap climbs as orders accumulate, GC compacts, then climbs again. Peak is clipped by GC.
- **Stream API / Iterator**: Steady climb to ~135-153 MB with only 1 GC. The high peak includes accumulated garbage from processed pages that hasn't been collected yet.
- **Pooled Iterator**: Smooth, gradual climb to only ~120 MB with **0 GC events**. No garbage from Order/String objects means no GC pressure and a genuinely lower peak.
- **Async**: Pronounced sawtooth with 3 GC cycles — the recursive CompletableFuture chains generate heavy garbage (390 MB/s allocation rate).
- **Reactor / Virtual Threads**: Similar shape to Iterator but finish faster. The post-peak dip visible in some runs is a single GC event.

---

## Key Observations

### 1. Async Takes Significantly Longer (1.30s vs 0.88-1.00s)

**Observed**: Async variant is ~30-50% slower than other streaming variants.

**Root Cause: Recursive CompletableFuture Composition Overhead**

The `AsyncIterableAggregator` uses recursive `thenCompose()` calls:

```java
// Simplified from AsyncIterableAggregator
private CompletableFuture<List<Order>> takeRecursive(
        AsyncIterator<Order> iterator,
        List<Order> result,
        int remaining) {

    return iterator.nextAsync().thenCompose(opt -> {
        if (opt.isPresent()) {
            result.add(opt.get());
            return takeRecursive(iterator, result, remaining - 1);  // Recursive!
        }
        return CompletableFuture.completedFuture(result);
    });
}
```

**Performance penalties:**

1. **Deep future chains**: Each of the 500,000 orders creates a new level in the CompletableFuture chain
2. **Object allocation**: Every `thenCompose()` allocates intermediate Future objects
3. **Atomic variable overhead**: The `AsyncPaginatedIterator` maintains 5 atomic references with memory barriers:
   - `AtomicReference<String> currentCursor`
   - `AtomicReference<Iterator<T>> currentPageIterator`
   - `AtomicBoolean finished`
   - `AtomicBoolean cancelled`
   - `AtomicBoolean firstPageFetched`
4. **Async HTTP overhead**: Uses `HttpClient.sendAsync()` with `delayedExecutor()` for retries

**Contrast with faster variants:**
- Streaming/Iterator/Naive: Simple while-loops with blocking HTTP
- Reactor: Uses efficient `reduce()` operator (no recursion)
- Virtual Threads: Blocking code on lightweight threads (no recursion penalty)

---

### 2. Naive Has Lower Peak Heap Than Streaming Variants (113 MB vs 135-153 MB)

**Observed**: Counter-intuitively, Naive shows a lower Peak Heap than Iterator/Stream/Reactor despite buffering all orders.

**This is a measurement artifact, not a true memory advantage.**

**Why the numbers appear this way:**

1. **GC timing clips the observed peak**:
   - Naive had 3 GC events; the 100ms sampler likely captures post-GC values, missing the true peak between samples
   - Streaming variants had only 1 GC; their peaks reflect accumulated garbage that hasn't been collected yet

2. **Naive's memory pressure triggers GC, which "helps" its numbers**:
   - As the `ArrayList` grows and resizes (doubling capacity), it creates memory pressure that triggers GC
   - After each GC, dead objects are reclaimed, so the sampled peak looks lower
   - Paradoxically, higher memory pressure leads to better-looking benchmark numbers

3. **Framework overhead inflates streaming peaks**:
   - Stream API adds: `PaginatedSpliterator`, `ReferencePipeline`, `Sink`, `Collector` objects
   - Reactor adds: `Flux`, `Mono`, operator chain objects
   - These exist alongside uncollected garbage from previous pages

**The Pooled Iterator proves this explanation:**

The Pooled Iterator achieves 110 MB peak with **0 GC events** — genuinely low memory, not an artifact. It avoids creating Order/String objects entirely, so there's no garbage to inflate the peak. This confirms that the Iterator's 135 MB peak is dominated by uncollected garbage, not by the working set itself.

**The reality at scale:**
```
Naive memory usage:     O(total_orders)  = O(500,000 orders)
Streaming memory usage: O(page_size)     = O(1,000 orders)
```

At even larger scales, Naive would require proportionally more memory while streaming variants stay constant at O(page_size).

**What Avg Heap reveals:**
- All variants show similar Avg Heap (60-74 MB)
- This reflects the baseline JVM footprint + active working set
- Streaming variants' working set is genuinely smaller during steady-state processing

---

### 3. Pooled Iterator Cuts Allocation Rate in Half (104 MB/s vs 228 MB/s)

**Observed**: The Pooled Iterator has half the allocation rate of the standard Iterator, zero GC events, and the lowest peak heap of any variant.

**Root Cause: Eliminating Order Object Allocation**

The standard Iterator creates 500,000 `Order` record objects (one per item) plus `String` objects for `id` and `status` fields, `Page` records, and `List.copyOf()` defensive copies. The Pooled Iterator uses Jackson's streaming `JsonParser` to extract only the `amount` field directly from JSON, skipping all object creation.

| Metric | Iterator | Pooled Iterator | Difference |
|--------|----------|-----------------|------------|
| Alloc Rate | 228 MB/s | 104 MB/s | **-54%** |
| Peak Heap | 135 MB | 110 MB | **-19%** |
| Avg Heap | 60 MB | 63 MB | **Similar** |
| GC Count | 1 | 0 | **Eliminated** |
| Duration | 0.93s | 0.97s | **Same** |

**Why peak heap is also lower (not just allocation rate):**

With zero GC events, there's no accumulated garbage from previous pages lingering in the young generation. The Iterator's 135 MB peak includes ~25 MB of dead `Order`/`String` objects awaiting their first (and only) GC. The Pooled variant never creates those objects, so the peak reflects the actual working set.

**Trade-off**: The Pooled Iterator is coupled to the JSON schema (it knows to look for the `"amount"` field). The standard Iterator uses generic `Order` deserialization, making it more flexible.

---

### 4. Virtual Threads Are Fastest (0.88s vs 0.91-1.00s for other streaming variants)

**Observed**: Virtual Threads is the fastest variant at 0.88s. Stream API, Reactor, Iterator, and Pooled Iterator cluster in the 0.91-1.00s range. Naive (0.99s) and Async (1.30s) trail behind.

**Root Cause: Blocking Code on Lightweight Threads**

The `VirtualThreadAggregator` runs the blocking `IterableAggregator` on a virtual thread via `CompletableFuture.supplyAsync()`:

```java
public class VirtualThreadAggregator {
    private final IterableAggregator delegate;

    public CompletableFuture<OrderStats> aggregateOrdersAsync() {
        return CompletableFuture.supplyAsync(
                delegate::aggregateOrders,
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }
}
```

The benchmark calls `aggregateOrdersAsync().join()`, which submits the work to a virtual thread executor and blocks until completion. During I/O waits (HTTP calls to fetch each page), the virtual thread parks and releases its carrier platform thread, allowing other work to proceed.

**Why it wins:**

| Factor | Virtual Threads | Other Variants |
|--------|-----------------|----------------|
| Code complexity | Simple blocking while-loop | Stream pipelines, reactive chains, or async recursion |
| I/O handling | Parks virtual thread, frees platform thread | Blocks platform thread or requires callbacks |
| Object allocation | Minimal (reuses IterableAggregator) | Framework objects, futures, operators |
| Thread cost | ~1KB per virtual thread | ~1MB per platform thread (or callback overhead) |
| Context switching | Lightweight continuation | Full thread context switch or callback dispatch |

**Comparison breakdown:**

- **vs Naive (0.99s)**: Naive waits for all pages before aggregating; Virtual Threads processes incrementally
- **vs Stream API (1.00s)**: Stream pipeline adds Spliterator/Collector overhead
- **vs Iterator (0.93s)**: Nearly identical logic, but virtual thread parks during I/O instead of blocking a platform thread
- **vs Pooled Iterator (0.97s)**: Lower allocation rate doesn't compensate for virtual thread I/O efficiency
- **vs Async (1.30s)**: No recursive future chains, no atomic variable overhead
- **vs Reactor (0.91s)**: No reactive framework overhead

---

## Implementation Comparison

| Aspect | Naive | Stream API | Iterator | Pooled Iterator | Async | Reactor | Virtual Threads |
|--------|-------|------------|----------|-----------------|-------|---------|-----------------|
| **HTTP calls** | Blocking | Blocking | Blocking | Blocking | Async | Blocking | Blocking (on virtual thread) |
| **Memory model** | O(n) all orders | O(page) streaming | O(page) streaming | O(page) streaming | O(page) streaming | O(page) streaming | O(page) streaming |
| **Processing** | Post-fetch loop | Stream.collect() | While-loop | Streaming JSON parse | Recursive futures | Flux.reduce() | While-loop |
| **Thread usage** | 1 platform thread | 1 platform thread | 1 platform thread | 1 platform thread | ForkJoinPool | Reactor scheduler | 1 virtual thread |
| **Framework overhead** | None | Stream API | None | None | CompletableFuture | Project Reactor | Minimal |
| **Object allocation** | All orders in List | Order + Stream infra | Order per page | Amounts only (no Order) | Order + Future chains | Order + Flux operators | Order per page |

---

## Lessons Learned

### 1. Async ≠ Faster

Asynchronous code introduces overhead:
- Future object allocation
- Callback dispatch
- Thread pool coordination
- Atomic synchronization

For I/O-bound workloads with simple aggregation, blocking code on virtual threads often outperforms async frameworks.

### 2. Peak Heap Can Be Misleading

Point-in-time measurements are affected by:
- GC timing relative to sampling
- Framework object lifecycle
- Allocation patterns (bulk vs continuous)

**The Pooled Iterator demonstrates this clearly:** it achieves the lowest peak heap (110 MB) with 0 GC events, proving that other variants' peaks are inflated by uncollected garbage rather than actual working set size.

**Better metrics for memory efficiency:**
- Avg Heap (steady-state usage)
- Memory growth pattern (should stay flat for streaming)
- Allocation rate (lower = less GC pressure)
- Behavior at larger scales (10x, 100x the data)

### 3. Virtual Threads Enable "Best of Both Worlds"

- **Write simple blocking code** (easy to understand, debug, maintain)
- **Get efficient I/O handling** (virtual threads park during I/O)
- **Avoid async complexity** (no callbacks, no reactive operators)

This makes Virtual Threads the recommended approach for new Java 21+ applications with I/O-bound workloads.

### 4. Framework Overhead is Real

Stream API, Reactor, and CompletableFuture all add measurable overhead:
- Object allocations for pipeline/operator infrastructure
- Method dispatch through abstraction layers
- Memory for maintaining framework state

For performance-critical paths, simpler is often faster.

### 5. Buffer Reuse Reduces Allocation Rate, Not Necessarily Heap

The Pooled Iterator eliminates ~103 MB of total object allocation (500K Order records + Strings), cutting allocation rate by more than half and eliminating GC entirely. However, the reduction in peak heap (110 vs 135 MB) is largely because there's no accumulated garbage — the actual per-page working set was already tiny (~150 KB for 1,000 orders).

**When buffer reuse matters:**
- Latency-sensitive workloads where GC pauses are unacceptable
- Very high throughput scenarios where allocation rate becomes a bottleneck
- Long-running processes where GC overhead accumulates

**When it doesn't matter much:**
- Short-lived batch jobs (like this benchmark)
- Workloads where I/O latency dominates computation

---

## Recommendations

| Use Case | Recommended Variant | Reason |
|----------|---------------------|--------|
| Java 21+ new development | Virtual Threads | Best performance, simplest code |
| Java 11-17 | Iterator or Stream API | Good balance of simplicity and efficiency |
| Backpressure required | Reactor | Built-in backpressure support |
| Existing async codebase | Async (with optimization) | Consistency with existing patterns |
| Memory-constrained | Any streaming variant | O(page_size) vs O(total_orders) |
| Zero-GC / low-latency | Pooled Iterator | Lowest allocation rate, no GC pauses |

---

## Reproducing This Analysis

```bash
# Run with same parameters as this analysis
./benchmark.sh --orders=500000 --pageSize=1000 --cpus=1

# Or with custom interval for more granular sampling
./benchmark.sh --orders=500000 --pageSize=1000 --cpus=1 --interval=50
```

The benchmark runs each variant in an isolated Docker container to ensure accurate, independent measurements.
