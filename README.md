# Java Streaming Aggregate Examples

Demonstrates how to stream and aggregate results from paginated JSON HTTP APIs **without buffering entire pages or all items in memory**.

This project provides **five implementation variants** with identical memory efficiency but different programming styles.

## Problem

When aggregating data from paginated APIs, naive implementations buffer all items before computing results:

```java
// Bad: O(total_items) memory - buffers everything
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

Process items **one at a time** without buffering:

```java
// Good: O(page_size) memory - items processed as they arrive
double sum = StreamSupport.stream(new PaginatedSpliterator<>(
        cursor -> objectMapper.readValue(httpClient.send(request(cursor)).body(), pageType),
        new CursorBasedPagination()
), false).mapToDouble(Order::amount).sum();
```

This project provides five variants with O(page_size) memory overhead:

| Variant | Package | Style | Blocking | Aggregation | Java | Docs |
|---------|---------|-------|----------|-------------|------|------|
| **Stream API** | `com.example.streaming` | Functional | Yes | `collect()` | 8+ | [javastream.md](docs/javastream.md) |
| **Iterator** | `com.example.iterable` | Imperative | Yes | Loop | 8+ | [iterator.md](docs/iterator.md) |
| **Async** | `com.example.async` | Future-based | No | Recursion | 8+ | [async.md](docs/async.md) |
| **Reactor** | `com.example.reactor` | Reactive | No | `reduce()` | 8+ | [reactor.md](docs/reactor.md) |
| **Virtual Threads** | `com.example.virtualthreads` | Imperative | Yes* | Loop | 21+ | [virtualthreads.md](docs/virtualthreads.md) |

*Virtual threads use blocking I/O but are cheap (~1KB vs ~1MB for platform threads), allowing millions of concurrent operations.

### Which Variant to Choose?

| Scenario | Recommendation |
|----------|----------------|
| Java 8-20, simple use case | Stream API or Iterator |
| Java 8-20, non-blocking required | Async |
| Java 8-20, backpressure needed | Reactor |
| Java 21+, simple code preference | Virtual Threads |
| Java 21+, reactive ecosystem integration | Reactor |

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
