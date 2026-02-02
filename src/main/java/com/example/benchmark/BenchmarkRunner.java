package com.example.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Orchestrates benchmark runs with memory sampling.
 *
 * <p>Runs a given task while collecting memory metrics at regular intervals
 * in a background thread.
 */
public class BenchmarkRunner {

    private final int sampleIntervalMs;
    private final MemoryCollector collector;

    /**
     * Creates a new benchmark runner.
     *
     * @param sampleIntervalMs interval between memory samples in milliseconds
     */
    public BenchmarkRunner(int sampleIntervalMs) {
        this.sampleIntervalMs = sampleIntervalMs;
        this.collector = new MemoryCollector();
    }

    /**
     * Runs a benchmark for the given task.
     *
     * @param variantName name of the variant being benchmarked
     * @param task        the task to run (should complete and return a result)
     * @param <T>         the result type
     * @return benchmark result with memory samples
     */
    public <T> BenchmarkResult run(String variantName, Supplier<T> task) {
        List<MemoryMetrics> samples = Collections.synchronizedList(new ArrayList<>());

        // Force GC before starting to get a clean baseline
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Start background sampling
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-sampler");
            t.setDaemon(true);
            return t;
        });

        sampler.scheduleAtFixedRate(
                () -> samples.add(collector.collect()),
                0,
                sampleIntervalMs,
                TimeUnit.MILLISECONDS
        );

        // Run the actual task
        long startTime = System.currentTimeMillis();
        try {
            task.get();
        } finally {
            // Stop sampling
            sampler.shutdown();
            try {
                sampler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long endTime = System.currentTimeMillis();

        // Collect one final sample
        samples.add(collector.collect());

        return new BenchmarkResult(
                variantName,
                new ArrayList<>(samples),
                endTime - startTime
        );
    }

    /**
     * Runs a benchmark for a void task.
     *
     * @param variantName name of the variant being benchmarked
     * @param task        the task to run
     * @return benchmark result with memory samples
     */
    public BenchmarkResult run(String variantName, Runnable task) {
        return run(variantName, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Returns whether OS-level metrics are available.
     */
    public boolean hasOsMetrics() {
        return collector.hasOsMetrics();
    }
}
