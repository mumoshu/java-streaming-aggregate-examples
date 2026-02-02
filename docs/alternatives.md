# Alternatives

If you need different capabilities beyond what this project provides, consider these alternatives.

## Reactive Streams / Flow API

For backpressure support and true reactive programming:

| Library | Description |
|---------|-------------|
| **[Project Reactor](https://projectreactor.io/)** | `Flux<T>` with backpressure, used by Spring WebFlux |
| **[RxJava](https://github.com/ReactiveX/RxJava)** | `Observable<T>` / `Flowable<T>` with rich operators |
| **[java.util.concurrent.Flow](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/Flow.html)** | Built-in reactive streams (Java 9+), requires more boilerplate |
| **[Mutiny](https://smallrye.io/smallrye-mutiny/)** | Simplified reactive API, used by Quarkus |

```java
// Project Reactor example
Flux.generate(
    () -> initialCursor,
    (cursor, sink) -> {
        Page<Order> page = fetchPage(cursor);
        page.items().forEach(sink::next);
        if (!page.hasMore()) sink.complete();
        return page.nextCursor();
    })
    .map(Order::amount)
    .reduce(Double::sum);
```

## Kotlin Coroutines / Flow

For Kotlin projects with structured concurrency:

```kotlin
// Kotlin Flow example
flow {
    var cursor: String? = null
    do {
        val page = fetchPage(cursor)
        page.items.forEach { emit(it) }
        cursor = page.nextCursor
    } while (page.hasMore)
}
.map { it.amount }
.reduce { a, b -> a + b }
```

## Virtual Threads (Java 21+)

With virtual threads, blocking I/O becomes cheap:

```java
// Virtual threads make blocking code scale like async
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        // Blocking iterator is fine with virtual threads
        for (Order order : aggregator.iterateOrders()) {
            process(order);
        }
    });
}
```

## Database Cursors

For database pagination, use native cursor support:

| Database | Approach |
|----------|----------|
| **PostgreSQL** | `DECLARE CURSOR` / `FETCH` |
| **MongoDB** | `cursor.batchSize()` |
| **Elasticsearch** | Scroll API or `search_after` |

## GraphQL Connections

For GraphQL APIs with Relay-style pagination:

```graphql
query {
  orders(first: 100, after: "cursor") {
    edges { node { id amount } }
    pageInfo { hasNextPage endCursor }
  }
}
```

## When to Use Each

| Scenario | Recommendation |
|----------|----------------|
| Simple aggregation, Java 8+ | **Stream** or **Iterator** variant (this project) |
| High-concurrency server (Netty, Vert.x) | **Async** variant (this project) |
| Need backpressure | Project Reactor or RxJava |
| Kotlin project | Kotlin Flow |
| Java 21+ with many concurrent tasks | Virtual Threads + Iterator |
| Complex stream transformations | Project Reactor (rich operators) |
