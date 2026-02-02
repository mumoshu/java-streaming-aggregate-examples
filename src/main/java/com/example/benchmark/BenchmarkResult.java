package com.example.benchmark;

import java.util.List;

/**
 * Holds the results of a single benchmark run.
 *
 * @param variantName  name of the variant (e.g., "Stream API", "Iterator")
 * @param samples      list of memory samples collected during the run
 * @param durationMs   total duration of the benchmark run in milliseconds
 */
public record BenchmarkResult(
        String variantName,
        List<MemoryMetrics> samples,
        long durationMs
) {
    /**
     * Returns the peak heap usage during this run in bytes.
     */
    public long peakHeapUsed() {
        return samples.stream()
                .mapToLong(MemoryMetrics::heapUsed)
                .max()
                .orElse(0);
    }

    /**
     * Returns the peak heap usage in megabytes.
     */
    public double peakHeapUsedMB() {
        return peakHeapUsed() / (1024.0 * 1024.0);
    }

    /**
     * Returns the average heap usage during this run in bytes.
     */
    public long avgHeapUsed() {
        return (long) samples.stream()
                .mapToLong(MemoryMetrics::heapUsed)
                .average()
                .orElse(0);
    }

    /**
     * Returns the average heap usage in megabytes.
     */
    public double avgHeapUsedMB() {
        return avgHeapUsed() / (1024.0 * 1024.0);
    }

    /**
     * Returns the peak RSS during this run in bytes, or -1 if unavailable.
     */
    public long peakRss() {
        return samples.stream()
                .mapToLong(MemoryMetrics::rssBytes)
                .filter(v -> v > 0)
                .max()
                .orElse(-1);
    }

    /**
     * Returns the peak RSS in megabytes, or -1 if unavailable.
     */
    public double peakRssMB() {
        long peak = peakRss();
        return peak > 0 ? peak / (1024.0 * 1024.0) : -1;
    }

    /**
     * Returns the duration in seconds.
     */
    public double durationSec() {
        return durationMs / 1000.0;
    }

    /**
     * Estimates allocation rate in MB/s based on heap growth pattern.
     * This is a rough estimate based on the maximum observed heap growth.
     */
    public double estimatedAllocRateMBPerSec() {
        if (samples.size() < 2 || durationMs == 0) {
            return 0;
        }

        // Find max growth between consecutive samples (accounting for GC)
        long maxGrowth = 0;
        long totalGrowth = 0;
        int growthCount = 0;

        for (int i = 1; i < samples.size(); i++) {
            long growth = samples.get(i).heapUsed() - samples.get(i - 1).heapUsed();
            if (growth > 0) {
                maxGrowth = Math.max(maxGrowth, growth);
                totalGrowth += growth;
                growthCount++;
            }
        }

        // Use average growth rate if we have enough samples
        if (growthCount > 0) {
            double avgGrowthPerSample = totalGrowth / (double) growthCount;
            // Assuming 100ms sample interval
            double growthPerSec = avgGrowthPerSample * 10;
            return growthPerSec / (1024.0 * 1024.0);
        }

        return 0;
    }

    /**
     * Counts approximate GC events by detecting heap drops.
     */
    public int estimatedGcCount() {
        int gcCount = 0;
        for (int i = 1; i < samples.size(); i++) {
            long prev = samples.get(i - 1).heapUsed();
            long curr = samples.get(i).heapUsed();
            // Consider a drop of more than 10% as a GC event
            if (curr < prev * 0.9) {
                gcCount++;
            }
        }
        return gcCount;
    }
}
