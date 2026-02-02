package com.example.streaming.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Simple HTTP server for paginated API responses.
 * Uses the JDK's built-in com.sun.net.httpserver.HttpServer.
 *
 * <p>This server simulates a paginated orders API that returns:
 * <ul>
 *   <li>Configurable number of orders split across pages</li>
 *   <li>Cursor-based pagination</li>
 *   <li>Configurable page size</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try (SimpleOrdersServer server = SimpleOrdersServer.create(1000, 100)) {
 *     server.start();
 *     String baseUrl = server.getBaseUrl() + "/orders";
 *     // Use baseUrl...
 * }
 * }</pre>
 */
public class SimpleOrdersServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final int port;
    private final List<OrderData> orders;
    private final int pageSize;
    private final ObjectMapper objectMapper;

    /**
     * Creates a server with the specified orders and page size.
     *
     * @param totalOrders number of orders to generate
     * @param pageSize number of orders per page
     * @return configured server (not yet started)
     */
    public static SimpleOrdersServer create(int totalOrders, int pageSize) {
        try {
            return new SimpleOrdersServer(0, totalOrders, pageSize);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create server", e);
        }
    }

    /**
     * Creates a server on a specific port.
     */
    public static SimpleOrdersServer createOnPort(int port, int totalOrders, int pageSize) {
        try {
            return new SimpleOrdersServer(port, totalOrders, pageSize);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create server on port " + port, e);
        }
    }

    private SimpleOrdersServer(int port, int totalOrders, int pageSize) throws IOException {
        this.pageSize = pageSize;
        this.objectMapper = new ObjectMapper();
        this.orders = generateOrders(totalOrders);
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.port = server.getAddress().getPort();

        server.createContext("/orders", new OrdersHandler());
        this.executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
    }

    /**
     * Generates test orders with predictable values.
     * Order amounts follow the pattern: (index + 1) * 10.0
     */
    private List<OrderData> generateOrders(int count) {
        List<OrderData> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new OrderData(
                    "order-" + (i + 1),
                    (i + 1) * 10.0,
                    i % 3 == 0 ? "completed" : (i % 3 == 1 ? "pending" : "cancelled")
            ));
        }
        return result;
    }

    /**
     * Starts the server.
     */
    public void start() {
        server.start();
    }

    /**
     * Returns the base URL of the server.
     */
    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Returns the port the server is listening on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the total number of orders.
     */
    public int getTotalOrders() {
        return orders.size();
    }

    /**
     * Returns the expected sum of all order amounts.
     * Formula: sum of 10 + 20 + 30 + ... + (n * 10) = 10 * (n * (n + 1) / 2)
     */
    public double getExpectedSum() {
        int n = orders.size();
        return 10.0 * n * (n + 1) / 2;
    }

    /**
     * Returns the expected average of all order amounts.
     */
    public double getExpectedAverage() {
        return getExpectedSum() / orders.size();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Handler for the /orders endpoint.
     * Supports cursor-based pagination via the "cursor" query parameter.
     */
    private class OrdersHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Method not allowed");
                    return;
                }

                Map<String, String> params = parseQueryParams(exchange.getRequestURI());
                int offset = parseCursor(params.get("cursor"));

                // Calculate page boundaries
                int start = offset;
                int end = Math.min(offset + pageSize, orders.size());
                boolean hasMore = end < orders.size();

                // Build response
                List<OrderData> pageOrders = orders.subList(start, end);
                String nextCursor = hasMore ? encodeCursor(end) : null;

                PageResponse response = new PageResponse(pageOrders, nextCursor, hasMore);
                String json = objectMapper.writeValueAsString(response);

                sendJson(exchange, 200, json);
            } catch (Exception e) {
                sendError(exchange, 500, "Internal server error: " + e.getMessage());
            }
        }

        private Map<String, String> parseQueryParams(URI uri) {
            Map<String, String> params = new HashMap<>();
            String query = uri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length == 2) {
                        params.put(pair[0], pair[1]);
                    }
                }
            }
            return params;
        }

        private int parseCursor(String cursor) {
            if (cursor == null || cursor.isEmpty()) {
                return 0;
            }
            try {
                // Simple cursor format: base64 encoded offset
                return Integer.parseInt(
                        new String(java.util.Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8)
                );
            } catch (Exception e) {
                return 0;
            }
        }

        private String encodeCursor(int offset) {
            return java.util.Base64.getEncoder().encodeToString(
                    String.valueOf(offset).getBytes(StandardCharsets.UTF_8)
            );
        }

        private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        }

        private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
            String json = "{\"error\":\"" + message + "\"}";
            sendJson(exchange, statusCode, json);
        }
    }

    /**
     * Order data structure for JSON serialization.
     */
    public record OrderData(String id, double amount, String status) {
    }

    /**
     * Page response structure for JSON serialization.
     */
    public record PageResponse(
            List<OrderData> data,
            String nextCursor,
            boolean hasMore
    ) {
    }
}
