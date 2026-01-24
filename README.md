# Java Streaming Aggregate Examples

Demonstrates how to stream and aggregate results from paginated JSON HTTP APIs using Java Stream API **without buffering entire pages or all items in memory**.

## Problem

When aggregating data from paginated APIs, naive implementations buffer all items before computing results:

```java
// Bad: Buffers everything in memory
List<Order> allOrders = new ArrayList<>();
while (hasMorePages) {
    Page<Order> page = fetchPage(cursor);
    allOrders.addAll(page.items());  // Memory grows with each page
    cursor = page.nextCursor();
}
double sum = allOrders.stream().mapToDouble(Order::amount).sum();
```

This approach fails with large datasets (OutOfMemoryError) and wastes memory even for moderate ones.

## Solution

This project provides streaming primitives that process items **one at a time** with constant memory overhead:

```java
// Good: Streams lazily, only one page in memory at a time
StreamingAggregator aggregator = new StreamingAggregator("https://api.example.com/orders");
double sum = aggregator.streamOrders()
    .mapToDouble(Order::amount)
    .sum();
```
| Component | Memory Usage | Description |
|-----------|-------------|-------------|
| `PaginatedSpliterator` | O(page_size) | Only one page in memory at a time |
| `OrderStatsCollector` | O(1) | Aggregates without storing items |
| `StreamingJsonParser` | O(1) | Parses JSON items individually |

## Usage Examples

See the test files for detailed, runnable examples:

| Use Case | Test File |
|----------|-----------|
| **Basic aggregation** | [StreamingAggregatorIntegrationTest.java](src/test/java/com/example/streaming/StreamingAggregatorIntegrationTest.java) |
| **Lazy page fetching** | [PaginatedSpliteratorTest.java](src/test/java/com/example/streaming/PaginatedSpliteratorTest.java) |
| **Streaming collector** | [OrderStatsCollectorTest.java](src/test/java/com/example/streaming/OrderStatsCollectorTest.java) |
| **Memory-efficient JSON parsing** | [StreamingJsonParserTest.java](src/test/java/com/example/streaming/StreamingJsonParserTest.java) |

## API Response Format

```json
{
  "data": [
    {"id": "order-1", "amount": 150.00, "status": "completed"},
    {"id": "order-2", "amount": 75.50, "status": "pending"}
  ],
  "nextCursor": "eyJpZCI6MTAwfQ==",
  "hasMore": true
}
```

## Running Tests

```bash
make test
```
