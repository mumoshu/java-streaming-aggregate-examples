# Iterable-Based Lazy Aggregation

This sub-project demonstrates O(1) memory usage for aggregating large datasets using
**Iterable and Java Collections API with lazy loading**, as a contrast to the main
project's Stream API and Spliterator approach.

## Overview

Both approaches achieve the same goal: **process millions of items with constant memory**.
This sub-project shows how to accomplish this using traditional Java iteration patterns
instead of the Stream API.

## Quick Start

```java
// Create aggregator pointing to paginated API
IterableAggregator aggregator = new IterableAggregator("http://api.example.com/orders");

// Aggregate all orders (O(1) memory)
OrderStats stats = aggregator.aggregateOrders();

// Or iterate manually with for-each
for (Order order : aggregator.iterateOrders()) {
    // Process each order - only one page in memory at a time
}

// Filter while aggregating
OrderStats completedStats = aggregator.aggregateCompletedOrders();

// Early termination
OrderStats partial = aggregator.aggregateUntilAmount(100000.0);
```

## Components

| Component | Purpose |
|-----------|---------|
| `PaginatedIterator<T>` | Lazily fetches pages on demand via `hasNext()`/`next()` |
| `LazyPaginatedIterable<T>` | Reusable Iterable wrapper (creates fresh iterator each time) |
| `Aggregator<T, A, R>` | Interface for O(1) memory aggregation (parallel to Collector) |
| `OrderStatsAggregator` | Computes count, sum, average with O(1) memory |
| `IterableAggregator` | Main facade for order aggregation |

## Comparison: Stream vs Iterable Approach

| Aspect | Stream-Based (Main) | Iterable-Based (This Sub-Project) |
|--------|---------------------|-----------------------------------|
| Core abstraction | `Spliterator<T>` | `Iterator<T>` |
| Lazy evaluation | `tryAdvance(Consumer)` | `hasNext()` / `next()` |
| Aggregation | `Collector` + `stream.collect()` | `Aggregator.aggregate(Iterable)` |
| Filtering | `stream.filter(predicate)` | `if` in loop or `aggregateFiltered()` |
| Early termination | `limit()`, `takeWhile()`, `findFirst()` | `break` statement |
| Style | Functional/declarative | Imperative/explicit control flow |
| Memory | O(page_size) + O(1) | O(page_size) + O(1) |
| Parallel support | `trySplit()` (not used for pagination) | Not applicable |

## Why Both Approaches?

### Stream API (Main Project)
- Concise, functional style
- Rich set of operations (map, filter, flatMap, etc.)
- Integrates with Java's Collector ecosystem
- Good for developers familiar with functional programming

### Iterable API (This Sub-Project)
- Explicit control flow (easier to debug)
- Familiar for-each loop syntax
- No intermediate abstractions
- Good for developers preferring imperative style
- Easier to understand memory behavior

## Memory Model

```
┌─────────────────────────────────────────────────────────────┐
│ PaginatedIterator                   [Memory: O(page_size)] │
│  └─ Current page: ~100 items (~10KB)                       │
│     Previous pages: garbage collected                       │
└────────────────────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────┐
│ OrderStatsAggregator.Accumulator    [Memory: O(1)]         │
│  ├─ count: long (8 bytes)                                  │
│  └─ sum: double (8 bytes)                                  │
│     Orders are NOT stored                                   │
└─────────────────────────────────────────────────────────────┘

Total for 1 million orders: ~10KB (constant!)
```

## Usage Patterns

### Basic Aggregation
```java
OrderStats stats = new OrderStatsAggregator().aggregate(orders);
```

### Filtered Aggregation
```java
OrderStats stats = new OrderStatsAggregator().aggregateFiltered(
    orders,
    order -> "completed".equals(order.status())
);
```

### Manual Iteration with Early Exit
```java
for (Order order : orders) {
    process(order);
    if (shouldStop(order)) {
        break; // No more pages will be fetched
    }
}
```

### Custom Aggregation
```java
Aggregator<Order, MyAccumulator, MyResult> custom = Aggregator.of(
    MyAccumulator::new,
    MyAccumulator::add,
    MyAccumulator::toResult
);
MyResult result = custom.aggregate(orders);
```

## Package Structure

```
com.example.iterable/
├── IterableAggregator.java           # Main facade
├── LazyPaginatedIterable.java        # Reusable iterable wrapper
├── iterator/
│   └── PaginatedIterator.java        # Core lazy-loading iterator
└── aggregation/
    ├── Aggregator.java               # Aggregation interface
    └── OrderStatsAggregator.java     # O(1) stats aggregator
```

## Relationship to Main Project

This sub-project reuses the following components from the main project:
- `com.example.streaming.model.Order` - Domain model
- `com.example.streaming.model.OrderStats` - Aggregation result
- `com.example.streaming.model.Page` - Page wrapper
- `com.example.streaming.client.HttpPageFetcher` - HTTP client
- `com.example.streaming.pagination.PaginationStrategy` - Pagination interface
- `com.example.streaming.pagination.CursorBasedPagination` - Cursor pagination

## Running the Tests

```bash
# Run all iterable tests
mvn test -Dtest="com.example.iterable.*"

# Run specific test class
mvn test -Dtest="PaginatedIteratorTest"
mvn test -Dtest="OrderStatsAggregatorTest"
mvn test -Dtest="IterableAggregatorIntegrationTest"
```
