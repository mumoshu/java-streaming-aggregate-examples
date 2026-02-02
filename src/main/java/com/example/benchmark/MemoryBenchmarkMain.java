package com.example.benchmark;

import com.example.async.AsyncIterableAggregator;
import com.example.iterable.IterableAggregator;
import com.example.naive.NaiveAggregator;
import com.example.reactor.ReactorAggregator;
import com.example.streaming.StreamingAggregator;
import com.example.streaming.model.OrderStats;
import com.example.virtualthreads.VirtualThreadAggregator;

/**
 * Runs a memory benchmark for a single aggregation variant.
 *
 * <p>This benchmark connects to an external server and measures memory usage
 * for one variant at a time. Output is JSON for easy parsing by the orchestrator.
 *
 * <p>Usage:
 * <pre>
 * java -cp ... com.example.benchmark.MemoryBenchmarkMain \
 *     --url=http://server:8080/orders \
 *     --variant=naive \
 *     --pageSize=100 \
 *     --orders=100000 \
 *     --interval=100
 * </pre>
 *
 * <p>Variant values: naive, stream, iterator, async, reactor, virtual
 *
 * <p>Output: JSON to stdout:
 * <pre>
 * {"variant":"Naive","durationSec":2.34,"peakHeapMB":180,"allocRateMBs":45,"gcCount":3,"durationMs":2340,"samples":[{"timestampMs":123,"heapUsedMB":45.2},...]}
 * </pre>
 */
public class MemoryBenchmarkMain {

    private static final int DEFAULT_INTERVAL = 100;

    public static void main(String[] args) {
        String url = parseStringArg(args, "url", null);
        String variant = parseStringArg(args, "variant", null);

        if (url == null || variant == null) {
            System.err.println("Error: --url and --variant parameters are required");
            System.err.println("Usage: java ... MemoryBenchmarkMain --url=<url> --variant=<name> [--pageSize=N] [--orders=N] [--interval=N]");
            System.err.println("Variants: naive, stream, iterator, async, reactor, virtual");
            System.exit(1);
        }

        int sampleInterval = parseIntArg(args, "interval", DEFAULT_INTERVAL);

        try {
            BenchmarkResult result = runVariant(url, variant, sampleInterval);
            // Output JSON for orchestrator to parse
            System.out.println(toJson(result));
        } catch (Exception e) {
            System.err.println("Error running benchmark: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static BenchmarkResult runVariant(String url, String variant, int sampleInterval)
            throws InterruptedException {
        BenchmarkRunner runner = new BenchmarkRunner(sampleInterval);

        // Warm-up with same variant
        runVariantOnce(url, variant);

        // Force GC after warm-up
        System.gc();
        Thread.sleep(500);

        // Run the actual benchmark
        return runner.run(getVariantDisplayName(variant),
                () -> runVariantOnce(url, variant));
    }

    private static OrderStats runVariantOnce(String url, String variant) {
        return switch (variant.toLowerCase()) {
            case "naive" -> new NaiveAggregator(url).aggregateOrders();
            case "stream" -> new StreamingAggregator(url).aggregateOrders();
            case "iterator" -> new IterableAggregator(url).aggregateOrders();
            case "async" -> new AsyncIterableAggregator(url).aggregateOrdersAsync().join();
            case "reactor" -> new ReactorAggregator(url).aggregateOrders().block();
            case "virtual" -> new VirtualThreadAggregator(url).aggregateOrders();
            default -> throw new IllegalArgumentException("Unknown variant: " + variant +
                    ". Valid values: naive, stream, iterator, async, reactor, virtual");
        };
    }

    private static String getVariantDisplayName(String variant) {
        return switch (variant.toLowerCase()) {
            case "naive" -> "Naive";
            case "stream" -> "Stream API";
            case "iterator" -> "Iterator";
            case "async" -> "Async";
            case "reactor" -> "Reactor";
            case "virtual" -> "Virtual Threads";
            default -> variant;
        };
    }

    private static String toJson(BenchmarkResult r) {
        // Simple JSON output - no external dependencies needed
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append(String.format("\"variant\":\"%s\",", escapeJson(r.variantName())));
        sb.append(String.format("\"durationSec\":%.2f,", r.durationSec()));
        sb.append(String.format("\"peakHeapMB\":%.0f,", r.peakHeapUsedMB()));
        sb.append(String.format("\"avgHeapMB\":%.0f,", r.avgHeapUsedMB()));
        sb.append(String.format("\"allocRateMBs\":%.0f,", r.estimatedAllocRateMBPerSec()));
        sb.append(String.format("\"gcCount\":%d,", r.estimatedGcCount()));
        sb.append(String.format("\"durationMs\":%d,", r.durationMs()));

        // Include memory samples for graph rendering
        sb.append("\"samples\":[");
        var samples = r.samples();
        for (int i = 0; i < samples.size(); i++) {
            var s = samples.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format("{\"timestampMs\":%d,\"heapUsedMB\":%.2f}",
                    s.timestampMs(), s.heapUsedMB()));
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private static String parseStringArg(String[] args, String name, String defaultValue) {
        String prefix = "--" + name + "=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return defaultValue;
    }
}
