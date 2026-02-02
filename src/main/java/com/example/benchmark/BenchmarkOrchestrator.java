package com.example.benchmark;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates memory benchmarks by running server and variants in Docker containers.
 *
 * <p>This provides full process isolation for accurate memory measurement:
 * each variant runs in its own container with dedicated CPU and memory limits.
 *
 * <p>Usage:
 * <pre>
 * # Default: 100,000 orders, page sizes 10/100/1000, 2 CPU cores
 * java ... BenchmarkOrchestrator
 *
 * # Custom CPU cores
 * java ... BenchmarkOrchestrator --cpus=4
 *
 * # Custom orders and interval
 * java ... BenchmarkOrchestrator --orders=500000 --interval=50 --cpus=2
 *
 * # Single page size
 * java ... BenchmarkOrchestrator --pageSize=100
 * </pre>
 */
public class BenchmarkOrchestrator {

    private static final String[] VARIANTS = {"naive", "stream", "iterator", "async", "reactor", "virtual"};
    private static final int[] DEFAULT_PAGE_SIZES = {10, 100, 1000};
    private static final String NETWORK_NAME = "benchmark-net";
    private static final String SERVER_IMAGE = "streaming-benchmark-server";
    private static final String BENCHMARK_IMAGE = "streaming-benchmark-client";
    private static final String SERVER_CONTAINER = "benchmark-server";

    private static final int DEFAULT_ORDERS = 100_000;
    private static final int DEFAULT_INTERVAL = 100;
    private static final int DEFAULT_CPUS = 2;

    public static void main(String[] args) {
        int totalOrders = parseIntArg(args, "orders", DEFAULT_ORDERS);
        int sampleInterval = parseIntArg(args, "interval", DEFAULT_INTERVAL);
        int cpus = parseIntArg(args, "cpus", DEFAULT_CPUS);
        int[] pageSizes = parsePageSizes(args);

        try {
            runBenchmarks(totalOrders, sampleInterval, cpus, pageSizes);
        } catch (Exception e) {
            System.err.println("Benchmark failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void runBenchmarks(int totalOrders, int sampleInterval, int cpus, int[] pageSizes)
            throws Exception {

        printHeader(totalOrders, cpus, pageSizes);

        // Clean up any stale resources from previous interrupted runs
        System.out.println("Cleaning up stale resources...");
        cleanupStaleResources();

        // Build Docker images
        System.out.println("Building Docker images...");
        buildDockerImages();

        // Create Docker network
        createNetwork(NETWORK_NAME);

        Map<Integer, List<VariantResult>> allResults = new LinkedHashMap<>();

        try {
            for (int pageSize : pageSizes) {
                System.out.println("\n▶ Page size: " + pageSize);
                System.out.println("─".repeat(60));

                // Start server container
                System.out.println("  Starting server container...");
                startServerContainer(totalOrders, pageSize, cpus);
                waitForServerReady();

                try {
                    List<VariantResult> results = new ArrayList<>();

                    for (String variant : VARIANTS) {
                        System.out.print("  Running " + variant + "...");
                        System.out.flush();

                        VariantResult result = runVariantContainer(
                                variant, pageSize, totalOrders, sampleInterval, cpus);
                        results.add(result);

                        System.out.println(" done (" + String.format("%.2fs", result.durationSec) + ")");
                    }

                    allResults.put(pageSize, results);
                    System.out.println();
                    printSummaryTable(results);

                    // Render memory graphs for each variant
                    // Use max duration across all variants for consistent time scale
                    long maxDurationMs = results.stream()
                            .mapToLong(r -> r.durationMs)
                            .max()
                            .orElse(1000);
                    System.out.println("\n  Memory Usage Over Time:");
                    for (VariantResult r : results) {
                        printMemoryGraph(r, maxDurationMs);
                    }

                } finally {
                    stopServerContainer();
                }
            }

            // Cross-page-size comparisons
            if (pageSizes.length > 1) {
                printCrossPageSizeComparison(allResults, "Peak Heap MB",
                        r -> r.peakHeapMB, "%.0f MB");
                printCrossPageSizeComparison(allResults, "Duration (seconds)",
                        r -> r.durationSec, "%.2fs");
            }

        } finally {
            // Cleanup
            removeNetwork(NETWORK_NAME);
        }

        System.out.println("\n═══════════════════════════════════════════════════════════════");
        System.out.println("  BENCHMARK COMPLETE");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private static void cleanupStaleResources() {
        // Stop and remove any stale server container
        try {
            execIgnoreError("docker", "stop", SERVER_CONTAINER);
        } catch (Exception ignored) {
        }
        try {
            execIgnoreError("docker", "rm", "-f", SERVER_CONTAINER);
        } catch (Exception ignored) {
        }
        // Remove stale network (must be done after removing containers)
        try {
            execIgnoreError("docker", "network", "rm", NETWORK_NAME);
        } catch (Exception ignored) {
        }
    }

    private static void buildDockerImages() throws Exception {
        exec("docker", "build", "-t", SERVER_IMAGE, "-f", "Dockerfile.server", ".");
        exec("docker", "build", "-t", BENCHMARK_IMAGE, "-f", "Dockerfile.benchmark", ".");
    }

    private static void createNetwork(String name) throws Exception {
        exec("docker", "network", "create", name);
    }

    private static void removeNetwork(String name) {
        try {
            execIgnoreError("docker", "network", "rm", name);
        } catch (Exception ignored) {
        }
    }

    private static void startServerContainer(int orders, int pageSize, int cpus) throws Exception {
        // Remove if exists (from previous failed run)
        execIgnoreError("docker", "rm", "-f", SERVER_CONTAINER);

        exec("docker", "run", "-d",
                "--name", SERVER_CONTAINER,
                "--network", NETWORK_NAME,
                "--cpus", String.valueOf(cpus),
                "-e", "ORDERS=" + orders,
                "-e", "PAGE_SIZE=" + pageSize,
                SERVER_IMAGE);
    }

    private static void waitForServerReady() throws Exception {
        // Wait for server to be ready by checking container logs
        System.out.print("  Waiting for server to start...");
        System.out.flush();

        for (int i = 0; i < 30; i++) {
            Thread.sleep(1000);
            String logs = execOutput("docker", "logs", SERVER_CONTAINER);
            if (logs.contains("Server started")) {
                System.out.println(" ready");
                return;
            }
        }
        throw new RuntimeException("Server did not start within 30 seconds");
    }

    private static void stopServerContainer() {
        try {
            execIgnoreError("docker", "stop", SERVER_CONTAINER);
            execIgnoreError("docker", "rm", SERVER_CONTAINER);
        } catch (Exception ignored) {
        }
    }

    private static VariantResult runVariantContainer(
            String variant, int pageSize, int orders, int interval, int cpus) throws Exception {

        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm",
                "--network", NETWORK_NAME,
                "--cpus", String.valueOf(cpus),
                "-e", "VARIANT=" + variant,
                "-e", "ORDERS=" + orders,
                "-e", "PAGE_SIZE=" + pageSize,
                "-e", "INTERVAL=" + interval,
                "-e", "SERVER_URL=http://" + SERVER_CONTAINER + ":8080/orders",
                BENCHMARK_IMAGE
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Variant " + variant + " failed with exit code " + exitCode +
                    "\nOutput: " + output);
        }

        return parseJsonResult(output.toString().trim());
    }

    private static VariantResult parseJsonResult(String output) {
        // Find the JSON line in output (last line that looks like JSON)
        String jsonLine = null;
        for (String line : output.split("\n")) {
            line = line.trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                jsonLine = line;
            }
        }

        if (jsonLine == null) {
            throw new RuntimeException("No JSON output found in: " + output);
        }

        // Simple JSON parsing without external dependencies
        return new VariantResult(
                parseJsonString(jsonLine, "variant"),
                parseJsonDouble(jsonLine, "durationSec"),
                parseJsonDouble(jsonLine, "peakHeapMB"),
                parseJsonDouble(jsonLine, "avgHeapMB"),
                parseJsonDouble(jsonLine, "allocRateMBs"),
                parseJsonInt(jsonLine, "gcCount"),
                parseJsonLong(jsonLine, "durationMs"),
                parseJsonSamples(jsonLine)
        );
    }

    private static long parseJsonLong(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new RuntimeException("Key not found: " + key + " in " + json);
    }

    private static List<MemorySample> parseJsonSamples(String json) {
        List<MemorySample> samples = new ArrayList<>();

        // Find the samples array
        Pattern arrayPattern = Pattern.compile("\"samples\"\\s*:\\s*\\[(.*)\\]");
        Matcher arrayMatcher = arrayPattern.matcher(json);
        if (!arrayMatcher.find()) {
            return samples;
        }

        String samplesJson = arrayMatcher.group(1);
        // Parse each sample object
        Pattern samplePattern = Pattern.compile(
                "\\{\"timestampMs\":(\\d+),\"heapUsedMB\":([0-9.]+)\\}");
        Matcher sampleMatcher = samplePattern.matcher(samplesJson);

        while (sampleMatcher.find()) {
            long timestampMs = Long.parseLong(sampleMatcher.group(1));
            double heapUsedMB = Double.parseDouble(sampleMatcher.group(2));
            samples.add(new MemorySample(timestampMs, heapUsedMB));
        }

        return samples;
    }

    private static String parseJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        throw new RuntimeException("Key not found: " + key + " in " + json);
    }

    private static double parseJsonDouble(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.]+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        throw new RuntimeException("Key not found: " + key + " in " + json);
    }

    private static int parseJsonInt(String json, String key) {
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9]+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        throw new RuntimeException("Key not found: " + key + " in " + json);
    }

    private static void printHeader(int totalOrders, int cpus, int[] pageSizes) {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  MEMORY BENCHMARK (Docker Isolation)");
        System.out.println("  Orders: " + String.format("%,d", totalOrders));
        System.out.println("  Page sizes: " + formatPageSizes(pageSizes));
        System.out.println("  CPUs per container: " + cpus);
        System.out.println("  Variants: Naive, Stream API, Iterator, Async, Reactor, Virtual Threads");
        System.out.println("═══════════════════════════════════════════════════════════════");
    }

    private static String formatPageSizes(int[] sizes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(sizes[i]);
        }
        return sb.toString();
    }

    private static void printSummaryTable(List<VariantResult> results) {
        System.out.println("  ┌─────────────────┬──────────┬───────────┬──────────┬───────────┬────────────┐");
        System.out.println("  │ Variant         │ Duration │ Peak Heap │ Avg Heap │ Alloc Rate│ Est. GCs   │");
        System.out.println("  ├─────────────────┼──────────┼───────────┼──────────┼───────────┼────────────┤");

        for (VariantResult r : results) {
            System.out.printf("  │ %-15s │ %6.2fs  │ %6.0f MB │ %5.0f MB │ %5.0f MB/s│ %6d     │%n",
                    r.variant,
                    r.durationSec,
                    r.peakHeapMB,
                    r.avgHeapMB,
                    r.allocRateMBs,
                    r.gcCount);
        }
        System.out.println("  └─────────────────┴──────────┴───────────┴──────────┴───────────┴────────────┘");
    }

    private static final char[] GRAPH_CHARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
    private static final int GRAPH_WIDTH = 50;
    private static final int GRAPH_HEIGHT = 6;

    private static void printMemoryGraph(VariantResult result, long maxDurationMs) {
        List<MemorySample> samples = result.samples;
        if (samples == null || samples.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("  " + result.variant + " - Heap Used (MB)");

        // Get the first timestamp as baseline
        long startTime = samples.get(0).timestampMs;

        // Find min/max heap for Y-axis scaling
        double minHeap = Double.MAX_VALUE;
        double maxHeap = Double.MIN_VALUE;
        for (MemorySample s : samples) {
            minHeap = Math.min(minHeap, s.heapUsedMB);
            maxHeap = Math.max(maxHeap, s.heapUsedMB);
        }

        // Add padding and ensure min starts at 0 for clearer visualization
        double range = maxHeap - minHeap;
        if (range < 1) range = 1;
        maxHeap = maxHeap + range * 0.1;
        minHeap = 0; // Start from 0 for memory graphs
        range = maxHeap - minHeap;

        // Build array of values for each column based on time position
        // Each column represents a time slice of maxDurationMs / GRAPH_WIDTH
        double[] columnValues = new double[GRAPH_WIDTH];
        double msPerColumn = (double) maxDurationMs / GRAPH_WIDTH;

        // Get the actual duration of this variant (last sample time - first sample time)
        long variantDurationMs = samples.get(samples.size() - 1).timestampMs - startTime;

        for (int col = 0; col < GRAPH_WIDTH; col++) {
            double colStartMs = col * msPerColumn;
            double colEndMs = (col + 1) * msPerColumn;

            // If this column is beyond the variant's duration, leave it empty
            if (colStartMs > variantDurationMs) {
                columnValues[col] = -1;
                continue;
            }

            // Find samples that fall within this time range
            double maxInColumn = -1;
            for (MemorySample s : samples) {
                double sampleTimeMs = s.timestampMs - startTime;
                if (sampleTimeMs >= colStartMs && sampleTimeMs < colEndMs) {
                    maxInColumn = Math.max(maxInColumn, s.heapUsedMB);
                }
            }

            // If no sample in this column but within duration, interpolate from last value
            if (maxInColumn < 0) {
                // Find the last sample before this column
                double lastValue = -1;
                for (MemorySample s : samples) {
                    double sampleTimeMs = s.timestampMs - startTime;
                    if (sampleTimeMs < colStartMs) {
                        lastValue = s.heapUsedMB;
                    } else {
                        break;
                    }
                }
                columnValues[col] = lastValue;
            } else {
                columnValues[col] = maxInColumn;
            }
        }

        // Render graph rows (top to bottom)
        for (int row = GRAPH_HEIGHT - 1; row >= 0; row--) {
            // Y-axis label
            if (row == GRAPH_HEIGHT - 1) {
                System.out.printf("  %3.0f │", maxHeap);
            } else if (row == 0) {
                System.out.printf("  %3.0f │", minHeap);
            } else if (row == GRAPH_HEIGHT / 2) {
                System.out.printf("  %3.0f │", (minHeap + maxHeap) / 2);
            } else {
                System.out.print("      │");
            }

            // Graph content - each column is a time slice
            for (int col = 0; col < GRAPH_WIDTH; col++) {
                double v = columnValues[col];
                if (v < 0) {
                    // No data for this time slice (after variant finished)
                    System.out.print(" ");
                    continue;
                }

                double rowBottom = minHeap + (range * row / GRAPH_HEIGHT);
                double rowTop = minHeap + (range * (row + 1) / GRAPH_HEIGHT);

                if (v >= rowTop) {
                    System.out.print("█");
                } else if (v > rowBottom) {
                    double fraction = (v - rowBottom) / (rowTop - rowBottom);
                    int charIndex = (int) (fraction * GRAPH_CHARS.length);
                    charIndex = Math.max(0, Math.min(charIndex, GRAPH_CHARS.length - 1));
                    System.out.print(GRAPH_CHARS[charIndex]);
                } else {
                    System.out.print(" ");
                }
            }
            System.out.println();
        }

        // X-axis line
        System.out.print("      └");
        System.out.println("─".repeat(GRAPH_WIDTH));

        // X-axis labels (time)
        double maxDurationSec = maxDurationMs / 1000.0;
        String endLabel = String.format("%.1fs", maxDurationSec);
        System.out.print("      0");
        int padding = GRAPH_WIDTH - endLabel.length();
        System.out.print(" ".repeat(Math.max(1, padding)));
        System.out.println(endLabel);
    }

    private static double[] downsampleValues(double[] values, int targetWidth) {
        if (values.length <= targetWidth) {
            return values;
        }

        double[] result = new double[targetWidth];
        double step = (double) values.length / targetWidth;

        for (int i = 0; i < targetWidth; i++) {
            int start = (int) (i * step);
            int end = (int) ((i + 1) * step);
            end = Math.min(end, values.length);

            // Take max in bucket to preserve peaks
            double maxVal = values[start];
            for (int j = start + 1; j < end; j++) {
                maxVal = Math.max(maxVal, values[j]);
            }
            result[i] = maxVal;
        }

        return result;
    }

    private static void printCrossPageSizeComparison(
            Map<Integer, List<VariantResult>> allResults,
            String title,
            Function<VariantResult, Double> valueExtractor,
            String format) {

        System.out.println("\n═══════════════════════════════════════════════════════════════");
        System.out.println("  COMPARISON ACROSS PAGE SIZES (" + title + ")");
        System.out.println("═══════════════════════════════════════════════════════════════");

        // Header
        System.out.print("                    ");
        for (int pageSize : allResults.keySet()) {
            System.out.printf("  Page %-5d  ", pageSize);
        }
        System.out.println();
        System.out.println("  ─────────────────────────────────────────────────────────────");

        // Get variant names from first result set
        List<VariantResult> firstResults = allResults.values().iterator().next();
        for (int i = 0; i < VARIANTS.length; i++) {
            String variantDisplay = firstResults.get(i).variant;
            System.out.printf("  %-17s", variantDisplay);

            for (List<VariantResult> results : allResults.values()) {
                VariantResult r = results.get(i);
                double value = valueExtractor.apply(r);
                System.out.printf("  %9s  ", String.format(format, value));
            }
            System.out.println();
        }
        System.out.println("  ─────────────────────────────────────────────────────────────");

        // Add explanation
        if (title.contains("Heap")) {
            System.out.println("  → Naive: O(total_orders) - memory constant regardless of page size");
            System.out.println("  → Others: O(page_size) - memory scales with page size, not total orders");
        } else if (title.contains("Duration")) {
            System.out.println("  → More pages (smaller page size) = more HTTP requests = longer duration");
        }
    }

    private static void exec(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + String.join(" ", command));
        }
    }

    private static void execIgnoreError(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Drain output to prevent blocking
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            while (reader.readLine() != null) {
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
    }

    private static String execOutput(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return output.toString();
    }

    private static int parseIntArg(String[] args, String name, int defaultValue) {
        String prefix = "--" + name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                try {
                    return Integer.parseInt(arg.substring(prefix.length()));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid value for " + name + ": " + arg);
                }
            }
        }
        return defaultValue;
    }

    private static int[] parsePageSizes(String[] args) {
        String prefix = "--pageSize=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                try {
                    return new int[]{Integer.parseInt(arg.substring(prefix.length()))};
                } catch (NumberFormatException e) {
                    System.err.println("Invalid page size: " + arg);
                }
            }
        }
        return DEFAULT_PAGE_SIZES;
    }

    /**
     * Simple data class for variant benchmark results.
     */
    private record VariantResult(
            String variant,
            double durationSec,
            double peakHeapMB,
            double avgHeapMB,
            double allocRateMBs,
            int gcCount,
            long durationMs,
            List<MemorySample> samples
    ) {
    }

    /**
     * Simple data class for memory samples.
     */
    private record MemorySample(
            long timestampMs,
            double heapUsedMB
    ) {
    }
}
