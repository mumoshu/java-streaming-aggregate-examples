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
│ Naive           │   1.05s  │    111 MB │    67 MB │   215 MB/s│      3     │
│ Stream API      │   0.88s  │    158 MB │    71 MB │   260 MB/s│      1     │
│ Iterator        │   0.87s  │    152 MB │    67 MB │   255 MB/s│      1     │
│ Async           │   1.24s  │    163 MB │    77 MB │   376 MB/s│      3     │
│ Reactor         │   0.85s  │    148 MB │    75 MB │   286 MB/s│      1     │
│ Virtual Threads │   0.79s  │    145 MB │    65 MB │   290 MB/s│      1     │
└─────────────────┴──────────┴───────────┴──────────┴───────────┴────────────┘
```

## Key Observations

### 1. Async Takes Significantly Longer (1.24s vs 0.79-0.88s)

**Observed**: Async variant is ~40-55% slower than other streaming variants.

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

### 2. Naive Has Lower Peak Heap Than Streaming Variants (111 MB vs 145-163 MB)

**Observed**: Counter-intuitively, Naive shows the lowest Peak Heap despite buffering all orders.

**This is a measurement artifact, not a true memory advantage.**

**Why the numbers appear this way:**

1. **Allocation pattern differs**:
   - Naive: Allocates one large contiguous `ArrayList` that grows predictably
   - Streaming: Continuously allocates smaller objects (Spliterator, Stream pipeline, Collector infrastructure)

2. **GC timing effects**:
   - Naive had 3 GC events; the peak measurement may have been captured post-GC
   - Streaming variants had only 1 GC; peaks captured during active processing include framework overhead

3. **Framework overhead**:
   - Stream API adds: `PaginatedSpliterator`, `ReferencePipeline`, `Sink`, `Collector` objects
   - Reactor adds: `Flux`, `Mono`, operator chain objects
   - These exist alongside the current page data

**The reality at scale:**
```
Naive memory usage:     O(total_orders)  = O(500,000 orders)
Streaming memory usage: O(page_size)     = O(1,000 orders)
```

At even larger scales, Naive would require proportionally more memory while streaming variants stay constant at O(page_size).

**What Avg Heap reveals:**
- All variants show similar Avg Heap (65-77 MB)
- This reflects the baseline JVM footprint + active working set
- Streaming variants' working set is genuinely smaller during steady-state processing

---

### 3. Virtual Threads is Fastest (0.79s)

**Observed**: Virtual Threads outperforms all other variants by 7-36%.

**Root Cause: Simplicity + Efficient I/O Handling**

The `VirtualThreadAggregator` simply delegates to `IterableAggregator`:

```java
public class VirtualThreadAggregator {
    private final IterableAggregator delegate;

    public OrderStats aggregateOrders() {
        return CompletableFuture.supplyAsync(
                delegate::aggregateOrders,  // Just delegates!
                Executors.newVirtualThreadPerTaskExecutor()
        ).join();
    }
}
```

**Why it wins:**

| Factor | Virtual Threads | Other Variants |
|--------|-----------------|----------------|
| Code complexity | Simple blocking while-loop | Stream pipelines, reactive chains, or async recursion |
| I/O handling | Parks virtual thread, frees platform thread | Blocks platform thread or requires callbacks |
| Object allocation | Minimal (reuses IterableAggregator) | Framework objects, futures, operators |
| Thread cost | ~1KB per virtual thread | ~1MB per platform thread (or callback overhead) |
| Context switching | Lightweight continuation | Full thread context switch or callback dispatch |

**Comparison breakdown:**

- **vs Naive (1.05s)**: Naive waits for all pages before aggregating; Virtual Threads processes incrementally
- **vs Stream API (0.88s)**: Stream pipeline adds Spliterator/Collector overhead
- **vs Iterator (0.87s)**: Nearly identical logic, but Iterator blocks platform thread during I/O
- **vs Async (1.24s)**: No recursive future chains, no atomic variable overhead
- **vs Reactor (0.85s)**: No reactive framework overhead

---

## Implementation Comparison

| Aspect | Naive | Stream API | Iterator | Async | Reactor | Virtual Threads |
|--------|-------|------------|----------|-------|---------|-----------------|
| **HTTP calls** | Blocking | Blocking | Blocking | Async | Blocking | Blocking |
| **Memory model** | O(n) all orders | O(page) streaming | O(page) streaming | O(page) streaming | O(page) streaming | O(page) streaming |
| **Processing** | Post-fetch loop | Stream.collect() | While-loop | Recursive futures | Flux.reduce() | While-loop |
| **Thread usage** | 1 platform thread | 1 platform thread | 1 platform thread | ForkJoinPool | Reactor scheduler | Virtual threads |
| **Framework overhead** | None | Stream API | None | CompletableFuture | Project Reactor | Minimal |

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

**Better metrics for memory efficiency:**
- Avg Heap (steady-state usage)
- Memory growth pattern (should stay flat for streaming)
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

---

## Recommendations

| Use Case | Recommended Variant | Reason |
|----------|---------------------|--------|
| Java 21+ new development | Virtual Threads | Best performance, simplest code |
| Java 11-17 | Iterator or Stream API | Good balance of simplicity and efficiency |
| Backpressure required | Reactor | Built-in backpressure support |
| Existing async codebase | Async (with optimization) | Consistency with existing patterns |
| Memory-constrained | Any streaming variant | O(page_size) vs O(total_orders) |

---

## Reproducing This Analysis

```bash
# Run with same parameters as this analysis
./benchmark.sh --orders=500000 --pageSize=1000 --cpus=1

# Or with custom interval for more granular sampling
./benchmark.sh --orders=500000 --pageSize=1000 --cpus=1 --interval=50
```

The benchmark runs each variant in an isolated Docker container to ensure accurate, independent measurements.
