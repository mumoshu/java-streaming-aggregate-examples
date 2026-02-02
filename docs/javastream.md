# Stream API Variant

Functional/declarative style using Java Stream API with `Spliterator`.

**Package:** `com.example.streaming`

## Usage

```java
StreamingAggregator aggregator = new StreamingAggregator("https://api.example.com/orders");

// Aggregate all orders
OrderStats stats = aggregator.aggregateOrders();

// Or use stream operations
double sum = aggregator.streamOrders()
    .filter(o -> "completed".equals(o.status()))
    .mapToDouble(Order::amount)
    .sum();

// Early termination with limit()
List<Order> first5 = aggregator.streamOrders()
    .limit(5)
    .collect(Collectors.toList());
```

## Key Classes

| Class | Description |
|-------|-------------|
| `StreamingAggregator` | Main facade for stream-based aggregation |
| `PaginatedSpliterator<T>` | Lazy page fetching via `Spliterator` interface |
| `OrderStatsCollector` | O(1) memory collector for order statistics |
| `StreamingJsonParser` | Memory-efficient JSON parsing |

## Characteristics

| Aspect | Value |
|--------|-------|
| **Style** | Functional/declarative |
| **Blocking** | Yes |
| **Filtering** | `.filter()` |
| **Early exit** | `.limit()`, `.takeWhile()` |
| **Memory** | O(page_size) |

## Test Files

| Use Case | Test File |
|----------|-----------|
| Basic aggregation | [StreamingAggregatorIntegrationTest.java](../src/test/java/com/example/streaming/StreamingAggregatorIntegrationTest.java) |
| Lazy page fetching | [PaginatedSpliteratorTest.java](../src/test/java/com/example/streaming/PaginatedSpliteratorTest.java) |
| Streaming collector | [OrderStatsCollectorTest.java](../src/test/java/com/example/streaming/OrderStatsCollectorTest.java) |
| Memory-efficient JSON parsing | [StreamingJsonParserTest.java](../src/test/java/com/example/streaming/StreamingJsonParserTest.java) |
