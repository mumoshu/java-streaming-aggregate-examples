# Async Iterator Variant

Non-blocking style using `CompletableFuture` for async I/O.

**Package:** `com.example.async`

## Usage

```java
AsyncIterableAggregator aggregator = new AsyncIterableAggregator("https://api.example.com/orders");

// Non-blocking aggregation
aggregator.aggregateOrdersAsync()
    .thenAccept(stats -> System.out.println("Sum: " + stats.sum()));

// Async iteration
aggregator.iterateOrdersAsync()
    .forEachAsync(order -> process(order))
    .thenRun(() -> System.out.println("Done"));

// Take first N items asynchronously
aggregator.takeFirstAsync(5)
    .thenAccept(orders -> orders.forEach(this::display));

// Early termination with cancel()
AsyncIterator<Order> iterator = aggregator.iterateOrdersAsync();
iterator.nextAsync()
    .thenAccept(opt -> {
        opt.ifPresent(this::process);
        iterator.cancel();  // Stop fetching more pages
    });
```

## Key Classes

| Class | Description |
|-------|-------------|
| `AsyncIterableAggregator` | Main facade for async aggregation |
| `AsyncIterator<T>` | Async iteration interface with `nextAsync()` |
| `AsyncPaginatedIterator<T>` | Lazy async page fetching |
| `AsyncHttpPageFetcher<T>` | Non-blocking HTTP client using `HttpClient.sendAsync()` |
| `AsyncOrderStatsAggregator` | O(1) memory async aggregator |

## Characteristics

| Aspect | Value |
|--------|-------|
| **Style** | Future-based |
| **Blocking** | No |
| **Thread usage** | Frees thread on I/O |
| **Filtering** | predicate param |
| **Early exit** | `cancel()` |
| **Memory** | O(page_size) |
| **Best for** | High-concurrency servers |

## Test Files

| Use Case | Test File |
|----------|-----------|
| Async aggregation | [AsyncIterableAggregatorIntegrationTest.java](../src/test/java/com/example/async/AsyncIterableAggregatorIntegrationTest.java) |
| Async lazy fetching | [AsyncPaginatedIteratorTest.java](../src/test/java/com/example/async/AsyncPaginatedIteratorTest.java) |
