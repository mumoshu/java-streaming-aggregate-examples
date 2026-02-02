package com.example.streaming.server;

/**
 * Standalone entry point for running SimpleOrdersServer.
 *
 * <p>Usage:
 * <pre>
 * java -cp ... com.example.streaming.server.SimpleOrdersServerMain [port] [totalOrders] [pageSize]
 *
 * # Examples:
 * java -cp ... SimpleOrdersServerMain                    # port=8080, orders=100000, pageSize=100
 * java -cp ... SimpleOrdersServerMain 8080               # orders=100000, pageSize=100
 * java -cp ... SimpleOrdersServerMain 8080 50000         # pageSize=100
 * java -cp ... SimpleOrdersServerMain 8080 50000 500     # all specified
 * </pre>
 *
 * <p>The server runs until terminated (Ctrl+C or SIGTERM).
 */
public class SimpleOrdersServerMain {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_ORDERS = 100_000;
    private static final int DEFAULT_PAGE_SIZE = 100;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        int totalOrders = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_ORDERS;
        int pageSize = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PAGE_SIZE;

        SimpleOrdersServer server = SimpleOrdersServer.createOnPort(port, totalOrders, pageSize);
        server.start();

        System.out.println("Server started on port " + server.getPort());
        System.out.println("  Orders: " + totalOrders);
        System.out.println("  Page size: " + pageSize);
        System.out.println("  URL: " + server.getBaseUrl() + "/orders");
        System.out.println();
        System.out.println("Press Ctrl+C to stop...");

        // Register shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down server...");
            server.close();
            System.out.println("Server stopped.");
        }));

        // Keep the main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
