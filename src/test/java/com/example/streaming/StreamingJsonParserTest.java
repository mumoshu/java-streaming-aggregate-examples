package com.example.streaming;

import com.example.streaming.model.Order;
import com.example.streaming.parser.StreamingJsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for StreamingJsonParser - demonstrates memory-efficient JSON parsing.
 *
 * <h2>What StreamingJsonParser Does</h2>
 * <p>Uses Jackson's streaming API ({@link com.fasterxml.jackson.core.JsonParser})
 * to parse JSON arrays item-by-item without loading the entire document into memory.</p>
 *
 * <h2>Memory Model</h2>
 * <table border="1">
 *   <tr><th>Approach</th><th>Memory Usage</th><th>Description</th></tr>
 *   <tr><td>ObjectMapper.readValue()</td><td>O(n)</td><td>Loads entire JSON tree</td></tr>
 *   <tr><td>StreamingJsonParser</td><td>O(1)</td><td>One item at a time</td></tr>
 * </table>
 *
 * <h2>When to Use</h2>
 * <ul>
 *   <li>Large JSON responses that might not fit in memory</li>
 *   <li>Processing items as they arrive (early termination)</li>
 *   <li>Reducing GC pressure with many small objects</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * StreamingJsonParser<Order> parser = new StreamingJsonParser<>(Order.class);
 *
 * // Parse items from "data" array (default field name)
 * try (Stream<Order> orders = parser.parseItems(inputStream)) {
 *     double total = orders
 *         .filter(o -> "completed".equals(o.status()))
 *         .mapToDouble(Order::amount)
 *         .sum();
 * }
 *
 * // Parse with pagination metadata
 * var result = parser.parseWithMetadata(inputStream);
 * String nextCursor = result.nextCursor();
 * boolean hasMore = result.hasMore();
 * try (Stream<Order> orders = result.items()) {
 *     // process orders...
 * }
 * }</pre>
 *
 * <h2>JSON Format Expected</h2>
 * <pre>{@code
 * {
 *   "data": [                    // Array field (configurable)
 *     {"id": "1", "amount": 100.0, "status": "completed"},
 *     {"id": "2", "amount": 200.0, "status": "pending"}
 *   ],
 *   "nextCursor": "abc123",      // Optional pagination cursor
 *   "hasMore": true              // Optional pagination flag
 * }
 * }</pre>
 */
class StreamingJsonParserTest {

    private final StreamingJsonParser<Order> parser = new StreamingJsonParser<>(Order.class);

    // =========================================================================
    // BASIC PARSING
    // =========================================================================

    /**
     * Basic usage: parse items from a JSON response with "data" array.
     *
     * <p>The parser streams through the JSON, extracts items from the "data"
     * array, and deserializes each one individually.</p>
     */
    @Test
    @DisplayName("Should parse items from JSON array")
    void shouldParseItemsFromJsonArray() throws IOException {
        // Given
        String json = """
                {
                    "data": [
                        {"id": "1", "amount": 100.0, "status": "completed"},
                        {"id": "2", "amount": 200.0, "status": "pending"}
                    ],
                    "nextCursor": "abc123",
                    "hasMore": true
                }
                """;

        // When
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            List<Order> orderList = orders.collect(Collectors.toList());

            // Then
            assertThat(orderList).hasSize(2);
            assertThat(orderList.get(0).id()).isEqualTo("1");
            assertThat(orderList.get(0).amount()).isEqualTo(100.0);
            assertThat(orderList.get(1).id()).isEqualTo("2");
            assertThat(orderList.get(1).amount()).isEqualTo(200.0);
        }
    }

    // =========================================================================
    // EDGE CASES
    // =========================================================================

    /**
     * Handles empty arrays gracefully.
     */
    @Test
    @DisplayName("Should parse empty array")
    void shouldParseEmptyArray() throws IOException {
        // Given
        String json = """
                {
                    "data": [],
                    "nextCursor": null,
                    "hasMore": false
                }
                """;

        // When
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            List<Order> orderList = orders.collect(Collectors.toList());

            // Then
            assertThat(orderList).isEmpty();
        }
    }

    // =========================================================================
    // LAZY EVALUATION
    // =========================================================================

    /**
     * Demonstrates lazy streaming - items parsed only when consumed.
     *
     * <p>Using {@code limit(5)} on a stream of 100 items means only
     * the first 5 items are actually parsed from the JSON.</p>
     *
     * <p><b>Benefit:</b> Early termination with findFirst() or limit()
     * avoids parsing the entire JSON array.</p>
     */
    @Test
    @DisplayName("Should stream items lazily")
    void shouldStreamItemsLazily() throws IOException {
        // Given: Large JSON with many items
        StringBuilder json = new StringBuilder("{\"data\": [");
        for (int i = 0; i < 100; i++) {
            if (i > 0) json.append(",");
            json.append(String.format(
                    "{\"id\": \"%d\", \"amount\": %d.0, \"status\": \"completed\"}",
                    i, (i + 1) * 10
            ));
        }
        json.append("], \"nextCursor\": null, \"hasMore\": false}");

        // When: Take only first 5 items (lazy evaluation)
        try (Stream<Order> orders = parser.parseItems(toInputStream(json.toString()))) {
            List<Order> firstFive = orders
                    .limit(5)
                    .collect(Collectors.toList());

            // Then
            assertThat(firstFive).hasSize(5);
            assertThat(firstFive.get(0).id()).isEqualTo("0");
            assertThat(firstFive.get(4).id()).isEqualTo("4");
        }
    }

    // =========================================================================
    // JSON STRUCTURE FLEXIBILITY
    // =========================================================================

    /**
     * Handles fields appearing before the "data" array.
     *
     * <p>The parser skips over unrelated fields until it finds the
     * target array field.</p>
     */
    @Test
    @DisplayName("Should handle JSON with fields before data array")
    void shouldHandleFieldsBeforeDataArray() throws IOException {
        // Given: Fields appear before the data array
        String json = """
                {
                    "metadata": {"version": "1.0"},
                    "count": 2,
                    "data": [
                        {"id": "1", "amount": 100.0, "status": "completed"},
                        {"id": "2", "amount": 200.0, "status": "pending"}
                    ]
                }
                """;

        // When
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            List<Order> orderList = orders.collect(Collectors.toList());

            // Then
            assertThat(orderList).hasSize(2);
        }
    }

    /**
     * Supports custom array field names (not just "data").
     *
     * <p>Some APIs use different field names like "items", "results",
     * or "orders". The second constructor argument specifies the field name.</p>
     *
     * <pre>{@code
     * // For JSON with "orders" array instead of "data"
     * StreamingJsonParser<Order> parser = new StreamingJsonParser<>(
     *     Order.class,
     *     "orders"  // Custom field name
     * );
     * }</pre>
     */
    @Test
    @DisplayName("Should use custom array field name")
    void shouldUseCustomArrayFieldName() throws IOException {
        // Given: Different field name for the array
        String json = """
                {
                    "orders": [
                        {"id": "1", "amount": 100.0, "status": "completed"}
                    ]
                }
                """;

        StreamingJsonParser<Order> customParser = new StreamingJsonParser<>(Order.class, "orders");

        // When
        try (Stream<Order> orders = customParser.parseItems(toInputStream(json))) {
            List<Order> orderList = orders.collect(Collectors.toList());

            // Then
            assertThat(orderList).hasSize(1);
            assertThat(orderList.get(0).id()).isEqualTo("1");
        }
    }

    /**
     * Returns empty stream when the expected array field is not found.
     *
     * <p>If parsing JSON with "items" but looking for "data", the parser
     * returns an empty stream rather than throwing an exception.</p>
     */
    @Test
    @DisplayName("Should return empty stream when array field not found")
    void shouldReturnEmptyStreamWhenArrayNotFound() throws IOException {
        // Given: JSON without "data" field
        String json = """
                {
                    "items": [
                        {"id": "1", "amount": 100.0, "status": "completed"}
                    ]
                }
                """;

        // When: Looking for "data" but JSON has "items"
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            List<Order> orderList = orders.collect(Collectors.toList());

            // Then
            assertThat(orderList).isEmpty();
        }
    }

    // =========================================================================
    // STREAM PIPELINE COMPATIBILITY
    // =========================================================================

    /**
     * Works with filter() in the stream pipeline.
     *
     * <p>Filter operations are applied as items are parsed, so items
     * that don't match are discarded immediately without buffering.</p>
     */
    @Test
    @DisplayName("Should support filtering in stream pipeline")
    void shouldSupportFilteringInPipeline() throws IOException {
        // Given
        String json = """
                {
                    "data": [
                        {"id": "1", "amount": 100.0, "status": "completed"},
                        {"id": "2", "amount": 200.0, "status": "pending"},
                        {"id": "3", "amount": 300.0, "status": "completed"}
                    ]
                }
                """;

        // When: Filter to completed orders only
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            List<Order> completed = orders
                    .filter(o -> "completed".equals(o.status()))
                    .collect(Collectors.toList());

            // Then
            assertThat(completed).hasSize(2);
            assertThat(completed).allMatch(o -> "completed".equals(o.status()));
        }
    }

    /**
     * Works with map() and reduction operations.
     *
     * <p>Mapping to primitive types (mapToDouble) enables efficient
     * aggregation without boxing overhead.</p>
     */
    @Test
    @DisplayName("Should support mapping in stream pipeline")
    void shouldSupportMappingInPipeline() throws IOException {
        // Given
        String json = """
                {
                    "data": [
                        {"id": "1", "amount": 100.0, "status": "completed"},
                        {"id": "2", "amount": 200.0, "status": "pending"}
                    ]
                }
                """;

        // When: Extract amounts
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            double totalAmount = orders
                    .mapToDouble(Order::amount)
                    .sum();

            // Then
            assertThat(totalAmount).isEqualTo(300.0);
        }
    }

    // =========================================================================
    // PAGINATION METADATA
    // =========================================================================

    /**
     * Extracts pagination metadata (nextCursor, hasMore) alongside items.
     *
     * <p>The {@code parseWithMetadata()} method returns a result object
     * containing both the item stream and pagination information needed
     * to fetch the next page.</p>
     *
     * <pre>{@code
     * var result = parser.parseWithMetadata(inputStream);
     * String cursor = result.nextCursor();  // For next request
     * boolean more = result.hasMore();       // Whether to continue
     * try (Stream<Order> items = result.items()) {
     *     // process items...
     * }
     * }</pre>
     */
    @Test
    @DisplayName("Should parse with metadata")
    void shouldParseWithMetadata() throws IOException {
        // Given
        String json = """
                {
                    "nextCursor": "cursor123",
                    "hasMore": true,
                    "data": [
                        {"id": "1", "amount": 100.0, "status": "completed"}
                    ]
                }
                """;

        // When
        var result = parser.parseWithMetadata(toInputStream(json));

        // Then
        assertThat(result.nextCursor()).isEqualTo("cursor123");
        assertThat(result.hasMore()).isTrue();

        try (Stream<Order> orders = result.items()) {
            List<Order> orderList = orders.collect(Collectors.toList());
            assertThat(orderList).hasSize(1);
        }
    }

    // =========================================================================
    // HANDLING COMPLEX JSON
    // =========================================================================

    /**
     * Ignores unknown properties in JSON items.
     *
     * <p>The Order record has @JsonIgnoreProperties(ignoreUnknown = true),
     * so extra fields in the JSON (like "customer") are silently ignored.</p>
     *
     * <p><b>Why this matters:</b> API responses often include fields you
     * don't need. Without this, Jackson would throw an exception.</p>
     */
    @Test
    @DisplayName("Should handle nested objects in items")
    void shouldHandleNestedObjects() throws IOException {
        // Given: Order with nested data (should be ignored by Order record)
        String json = """
                {
                    "data": [
                        {
                            "id": "1",
                            "amount": 100.0,
                            "status": "completed",
                            "customer": {"name": "John", "email": "john@example.com"}
                        }
                    ]
                }
                """;

        // When
        try (Stream<Order> orders = parser.parseItems(toInputStream(json))) {
            List<Order> orderList = orders.collect(Collectors.toList());

            // Then: Unknown properties are ignored
            assertThat(orderList).hasSize(1);
            assertThat(orderList.get(0).id()).isEqualTo("1");
        }
    }

    // =========================================================================
    // MEMORY EFFICIENCY
    // =========================================================================

    /**
     * Demonstrates O(1) memory usage for aggregation over large JSON.
     *
     * <p>This test parses 1000 items and computes a sum without ever
     * storing all items in memory. Each item is parsed, its amount extracted,
     * and then the item is discarded.</p>
     *
     * <p><b>Memory comparison:</b></p>
     * <pre>{@code
     * // BAD: O(n) memory - loads entire JSON tree
     * JsonNode root = objectMapper.readTree(json);
     * double sum = StreamSupport.stream(root.get("data").spliterator(), false)
     *     .mapToDouble(n -> n.get("amount").asDouble())
     *     .sum();
     *
     * // GOOD: O(1) memory - parses one item at a time
     * try (Stream<Order> orders = parser.parseItems(inputStream)) {
     *     double sum = orders.mapToDouble(Order::amount).sum();
     * }
     * }</pre>
     */
    @Test
    @DisplayName("Should aggregate orders efficiently")
    void shouldAggregateOrdersEfficiently() throws IOException {
        // Given: 1000 orders in JSON
        StringBuilder json = new StringBuilder("{\"data\": [");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) json.append(",");
            json.append(String.format(
                    "{\"id\": \"%d\", \"amount\": %d.0, \"status\": \"completed\"}",
                    i, (i + 1)
            ));
        }
        json.append("]}");

        // When: Stream and aggregate without buffering
        try (Stream<Order> orders = parser.parseItems(toInputStream(json.toString()))) {
            double sum = orders
                    .mapToDouble(Order::amount)
                    .sum();

            // Then: Sum of 1 + 2 + ... + 1000 = 500500
            assertThat(sum).isEqualTo(500500.0);
        }
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    /**
     * Converts a string to InputStream for testing.
     */
    private InputStream toInputStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
