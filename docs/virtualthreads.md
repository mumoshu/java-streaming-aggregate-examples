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
