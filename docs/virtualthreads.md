# Virtual Threads Variant

Simple blocking code that scales using Java 21+ virtual threads.

**Package:** `com.example.virtualthreads`

## Usage

```java
VirtualThreadAggregator aggregator = new VirtualThreadAggregator(
    "https://api.example.com/orders"
);

// Single aggregation on virtual thread
CompletableFuture<OrderStats> future = aggregator.aggregateOrdersAsync();
OrderStats stats = future.join();

// Or iterate with for-each (when already on virtual thread)
for (Order order : aggregator.iterateOrders()) {
    process(order);
    if (shouldStop(order)) {
        break;  // Early termination
    }
}

// Multiple concurrent aggregations - each on its own virtual thread
List<String> urls = List.of(
    "https://api1.example.com/orders",
    "https://api2.example.com/orders"
);
List<OrderStats> results = VirtualThreadAggregator.aggregateMultiple(urls);
```

## Key Classes

| Class | Description |
|-------|-------------|
| `VirtualThreadAggregator` | Main facade: delegates to `IterableAggregator`, runs on virtual threads |

## Why Virtual Threads?

Virtual threads allow blocking I/O code to scale without the complexity of async/reactive programming:

| Aspect | Platform Threads | Virtual Threads |
|--------|------------------|-----------------|
| Memory per thread | ~1MB | ~1KB |
| Max concurrent | ~thousands | ~millions |
| Blocking I/O | Wastes resources | Efficient |
| Code complexity | Simple | Simple |

```java
// Simple blocking code that scales!
public CompletableFuture<OrderStats> aggregateOrdersAsync() {
    return CompletableFuture.supplyAsync(
        delegate::aggregateOrders,  // Blocking call
        Executors.newVirtualThreadPerTaskExecutor()  // But on virtual thread
    );
}
```

## Characteristics

| Aspect | Value |
|--------|-------|
| **Style** | Imperative (blocking) |
| **Blocking** | Yes (but cheap) |
| **Core type** | `Iterable<T>` |
| **Aggregation** | Loop |
| **Backpressure** | No |
| **Memory** | O(page_size) |
| **Java version** | 21+ |

## Design Choices: Why Not Use AsyncIterableAggregator?

Java doesn't have an `await` keyword like JavaScript/C#/Kotlin. However, with virtual threads you can effectively get the same benefit by calling `.join()` or `.get()` on a `CompletableFuture` - because blocking on a virtual thread is cheap.

### `join()` vs `get()`

| Method | Checked Exception | Wraps Exception In |
|--------|-------------------|-------------------|
| `get()` | Yes - throws `InterruptedException`, `ExecutionException` | `ExecutionException` |
| `join()` | No - unchecked | `CompletionException` |

```java
// get() - must handle checked exceptions
try {
    OrderStats stats = future.get();
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException(e);
} catch (ExecutionException e) {
    throw new RuntimeException(e.getCause());
}

// join() - cleaner, unchecked exceptions
OrderStats stats = future.join();  // throws CompletionException if failed
```

**When to use which:**
- **`join()`**: Preferred in most cases, especially in lambdas/streams where checked exceptions are awkward
- **`get()`**: When you need timeout support (`get(5, TimeUnit.SECONDS)`) or explicit `InterruptedException` handling

We use `join()` in the examples for readability.

### Design Options

We considered three approaches:

### Option 1: Reuse IterableAggregator (Current)

Delegate to the blocking `IterableAggregator` on virtual threads:

```java
public CompletableFuture<OrderStats> aggregateOrdersAsync() {
    return CompletableFuture.supplyAsync(
        delegate::aggregateOrders,  // Blocking call
        Executors.newVirtualThreadPerTaskExecutor()
    );
}
```

**Pros:** Simple, no unnecessary abstraction layers.

### Option 2: Use AsyncIterableAggregator with Virtual Threads

Wrap `AsyncIterableAggregator` and call `.join()` on each future:

```java
public OrderStats aggregateOrders() {
    AsyncIterator<Order> iterator = asyncDelegate.asyncIterator();
    OrderStats stats = OrderStats.zero();

    while (iterator.hasNext().join()) {  // "await" via join()
        Order order = iterator.next().join();  // "await" via join()
        stats = stats.accumulate(order);
    }
    return stats;
}
```

**Why not:** This is redundant - it adds async overhead (futures, callbacks) only to immediately block on them. The `IterableAggregator` already does the same thing more directly.

### Option 3: Parallel Operations (Also Implemented)

Where virtual threads + async patterns add real value is **concurrent operations**:

```java
// Fetch from multiple APIs concurrently
List<CompletableFuture<OrderStats>> futures = urls.stream()
    .map(url -> CompletableFuture.supplyAsync(
        () -> new IterableAggregator(url).aggregateOrders(),
        virtualThreadExecutor))
    .toList();

// "await all" - blocking is cheap on virtual threads
return futures.stream().map(CompletableFuture::join).toList();
```

This is implemented in `aggregateMultiple()` and `aggregateMultipleCombined()`.

### Summary

| Approach | Value |
|----------|-------|
| Virtual threads + IterableAggregator | Simple blocking code that scales |
| Virtual threads + AsyncIterableAggregator | Redundant - adds complexity without benefit |
| Virtual threads + parallel operations | Useful for concurrent aggregations |

## Concurrent Aggregations

The real power of virtual threads shows with concurrent operations:

```java
// Aggregate from 100 different APIs concurrently
// Each runs on its own virtual thread - no thread pool sizing needed!
List<String> urls = getHundredApiUrls();
List<OrderStats> results = VirtualThreadAggregator.aggregateMultiple(urls);

// Or combine all results
OrderStats combined = VirtualThreadAggregator.aggregateMultipleCombined(urls);
```

## Implementation Details

### Reusing Existing Blocking Code

The virtual threads variant simply wraps the existing `IterableAggregator`:

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

### Concurrent Aggregations with Try-with-Resources

```java
public static List<OrderStats> aggregateMultiple(List<String> urls) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<OrderStats>> futures = urls.stream()
            .map(url -> executor.submit(() ->
                new IterableAggregator(url).aggregateOrders()))
            .toList();

        return futures.stream()
            .map(VirtualThreadAggregator::getFutureResult)
            .toList();
    }
}
```

## Test Files

| Use Case | Test File |
|----------|-----------|
| Basic aggregation | [VirtualThreadAggregatorIntegrationTest.java](../src/test/java/com/example/virtualthreads/VirtualThreadAggregatorIntegrationTest.java) |

## Requirements

- Java 21 or later
- No additional dependencies (uses standard library)
