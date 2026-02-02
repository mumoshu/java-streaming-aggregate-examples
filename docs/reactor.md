# Reactor Variant

Reactive style using Project Reactor's `Flux<T>` with `reduce()` operator.

**Package:** `com.example.reactor`

## Usage

```java
ReactorAggregator aggregator = new ReactorAggregator("https://api.example.com/orders");

// Aggregate all orders reactively
aggregator.aggregateOrders()
    .subscribe(stats -> System.out.println("Sum: " + stats.sum()));

// Stream orders with backpressure
aggregator.streamOrders()
    .filter(order -> "completed".equals(order.status()))
    .take(10)
    .subscribe(System.out::println);

// Blocking for testing
OrderStats stats = aggregator.aggregateOrders().block();
```

## Key Classes

| Class | Description |
|-------|-------------|
| `ReactorAggregator` | Main facade: `streamOrders()` → `Flux<Order>`, uses `reduce()` |
| `ReactorPageFetcher<T>` | Wraps `HttpPageFetcher`, returns `Mono<Page<T>>` |

## Why Reactor?

The key advantage over `CompletableFuture` is that aggregation uses the built-in `reduce()` operator instead of recursive `thenCompose()` calls:

```java
// CompletableFuture - requires recursion
private CompletableFuture<OrderStats> aggregateRecursive(String cursor, OrderStats acc) {
    return fetchPage(cursor).thenCompose(page -> {
        OrderStats newAcc = accumulate(acc, page);
        if (!page.hasNextPage()) {
            return CompletableFuture.completedFuture(newAcc);
        }
        return aggregateRecursive(page.nextCursor(), newAcc);  // Recursive call
    });
}

// Reactor - no recursion needed!
public Mono<OrderStats> aggregateOrders() {
    return streamOrders()
        .reduce(OrderStats.zero(), OrderStats::accumulate);
}
```

## Characteristics

| Aspect | Value |
|--------|-------|
| **Style** | Reactive |
| **Blocking** | No |
| **Core type** | `Flux<T>` / `Mono<T>` |
| **Aggregation** | `reduce()` operator |
| **Backpressure** | Yes |
| **Memory** | O(page_size) |
| **Java version** | 8+ |

## Operators

Reactor provides rich operators for stream processing:

```java
// Filter orders
aggregator.streamOrders()
    .filter(order -> "completed".equals(order.status()))
    .reduce(OrderStats.zero(), OrderStats::accumulate);

// Take first N
aggregator.streamOrders()
    .take(10)
    .collectList();

// Find first matching
aggregator.streamOrders()
    .filter(order -> order.amount() > 500)
    .next();

// Aggregate until condition
aggregator.streamOrders()
    .scan(OrderStats.zero(), OrderStats::accumulate)
    .takeWhile(stats -> stats.sum() < 10000)
    .last();
```

## Implementation Details

### Lazy Page Fetching with Flux.generate()

```java
public Flux<Order> streamOrders() {
    return Flux.generate(
        // Initial state: null cursor (first page)
        () -> new PaginationState(null, null, false),
        // Generator: fetch page and emit items
        (state, sink) -> emitNextItem(state, sink)
    );
}
```

Pages are only fetched as downstream subscribers request more items, providing natural backpressure support.

### Blocking I/O on Bounded Elastic Scheduler

The `ReactorPageFetcher` wraps blocking HTTP calls:

```java
public Mono<Page<T>> fetchPage(String cursor) {
    return Mono.fromCallable(() -> delegate.fetchPage(cursor))
        .subscribeOn(Schedulers.boundedElastic());
}
```

## Test Files

| Use Case | Test File |
|----------|-----------|
| Basic aggregation | [ReactorAggregatorIntegrationTest.java](../src/test/java/com/example/reactor/ReactorAggregatorIntegrationTest.java) |

## Dependencies

```xml
<dependency>
    <groupId>io.projectreactor</groupId>
    <artifactId>reactor-core</artifactId>
    <version>3.6.0</version>
</dependency>
```
