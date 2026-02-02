# Java Streaming Aggregate Examples

Demonstrates how to stream and aggregate results from paginated JSON HTTP APIs **without buffering entire pages or all items in memory**.

This project provides **three implementation variants** with identical memory efficiency but different programming styles.

## Problem

When aggregating data from paginated APIs, naive implementations buffer all items before computing results:

```java
// Bad: Buffers the entire result in memory
List<Order> allOrders = new ArrayList<>();
while (hasMorePages) {
    byte[] responseBody = httpClient.send(request).body();  // Entire response in memory
    Page<Order> page = objectMapper.readValue(responseBody, pageType);  // Both bytes + objects in memory
    allOrders.addAll(page.items());  // Memory grows with each page
    cursor = page.nextCursor();
    // responseBody and page can be GC'd here, but allOrders keeps growing
}
double sum = allOrders.stream().mapToDouble(Order::amount).sum();
```

This approach fails with large datasets (OutOfMemoryError) and wastes memory even for moderate ones.

## Solution

This project provides three variants that process items **one at a time** with O(page_size) memory overhead:

| Variant | Package | Style | Blocking | Iterator | Aggregator | Docs |
|---------|---------|-------|----------|----------|------------|------|
| **Stream API** | `com.example.streaming` | Functional | Yes | `PaginatedSpliterator` | `OrderStatsCollector` | [javastream.md](docs/javastream.md) |
| **Iterator** | `com.example.iterable` | Imperative | Yes | `PaginatedIterator` | `OrderStatsAggregator` | [iterator.md](docs/iterator.md) |
| **Async** | `com.example.async` | Future-based | No | `AsyncPaginatedIterator` | `AsyncOrderStatsAggregator` | [async.md](docs/async.md) |

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

## Alternatives

For different capabilities (backpressure, reactive streams, Kotlin, etc.), see [docs/alternatives.md](docs/alternatives.md).

## Running Tests

```bash
make test
```
