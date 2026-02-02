# Iterator Variant

Imperative style using standard Java `Iterator`/`Iterable`.

**Package:** `com.example.iterable`

## Usage

```java
IterableAggregator aggregator = new IterableAggregator("https://api.example.com/orders");

// Aggregate all orders
OrderStats stats = aggregator.aggregateOrders();

// Or iterate with for-each
for (Order order : aggregator.iterateOrders()) {
    process(order);
    if (shouldStop(order)) {
        break;  // Early termination - no more pages fetched
    }
}

// Filter with if statement
for (Order order : aggregator.iterateOrders()) {
    if ("completed".equals(order.status())) {
        process(order);
    }
}
```

## Key Classes

| Class | Description |
|-------|-------------|
| `IterableAggregator` | Main facade for iterator-based aggregation |
| `PaginatedIterator<T>` | Lazy page fetching via `Iterator` interface |
| `LazyPaginatedIterable<T>` | Reusable `Iterable` wrapper |
| `OrderStatsAggregator` | O(1) memory aggregator for order statistics |

## Characteristics

| Aspect | Value |
|--------|-------|
| **Style** | Imperative |
| **Blocking** | Yes |
| **Filtering** | `if` statement |
| **Early exit** | `break` |
| **Memory** | O(page_size) |

## Test Files

| Use Case | Test File |
|----------|-----------|
| Basic aggregation | [IterableAggregatorIntegrationTest.java](../src/test/java/com/example/iterable/IterableAggregatorIntegrationTest.java) |
| Lazy page fetching | [PaginatedIteratorTest.java](../src/test/java/com/example/iterable/PaginatedIteratorTest.java) |
| Iterator-based aggregator | [OrderStatsAggregatorTest.java](../src/test/java/com/example/iterable/OrderStatsAggregatorTest.java) |
