package com.example.benchmark;

/**
 * Holds a single memory measurement sample.
 *
 * @param timestampMs   Unix timestamp when this sample was taken
 * @param heapUsed      JVM heap memory currently in use (bytes)
 * @param heapCommitted JVM heap memory committed (bytes)
 * @param heapMax       JVM maximum heap size (bytes)
 * @param nonHeapUsed   JVM non-heap memory in use (bytes) - metaspace, code cache, etc.
 * @param rssBytes      OS Resident Set Size - physical memory used (bytes), -1 if unavailable
 * @param vsizeBytes    OS Virtual memory size (bytes), -1 if unavailable
 */
public record MemoryMetrics(
        long timestampMs,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        long nonHeapUsed,
        long rssBytes,
        long vsizeBytes
) {
    /**
     * Returns heap used in megabytes.
     */
    public double heapUsedMB() {
        return heapUsed / (1024.0 * 1024.0);
    }

    /**
     * Returns heap committed in megabytes.
     */
    public double heapCommittedMB() {
        return heapCommitted / (1024.0 * 1024.0);
    }

    /**
     * Returns RSS in megabytes, or -1 if unavailable.
     */
    public double rssMB() {
        return rssBytes > 0 ? rssBytes / (1024.0 * 1024.0) : -1;
    }

    /**
     * Returns virtual memory in megabytes, or -1 if unavailable.
     */
    public double vsizeMB() {
        return vsizeBytes > 0 ? vsizeBytes / (1024.0 * 1024.0) : -1;
    }
}
