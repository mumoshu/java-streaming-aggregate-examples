package com.example.benchmark;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Collects memory metrics from both JVM and OS.
 *
 * <p>JVM metrics are collected via {@link MemoryMXBean}.
 * OS metrics (RSS, virtual memory) are collected from /proc/self/status on Linux.
 */
public class MemoryCollector {

    private final MemoryMXBean memoryMXBean;
    private final boolean isLinux;

    public MemoryCollector() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.isLinux = System.getProperty("os.name").toLowerCase().contains("linux");
    }

    /**
     * Collects a snapshot of current memory metrics.
     *
     * @return current memory metrics
     */
    public MemoryMetrics collect() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();

        long rss = -1;
        long vsize = -1;

        if (isLinux) {
            rss = readProcValue("VmRSS");
            vsize = readProcValue("VmSize");
        }

        return new MemoryMetrics(
                System.currentTimeMillis(),
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                rss,
                vsize
        );
    }

    /**
     * Reads a value from /proc/self/status.
     *
     * <p>Format example:
     * <pre>
     * VmRSS:    123456 kB
     * VmSize:   234567 kB
     * </pre>
     *
     * @param key the key to look for (e.g., "VmRSS", "VmSize")
     * @return value in bytes, or -1 if not found
     */
    private long readProcValue(String key) {
        Path procStatus = Path.of("/proc/self/status");
        if (!Files.exists(procStatus)) {
            return -1;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(procStatus.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(key + ":")) {
                    // Parse "VmRSS:    123456 kB"
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long valueKB = Long.parseLong(parts[1]);
                        return valueKB * 1024; // Convert to bytes
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            // Ignore - return -1
        }

        return -1;
    }

    /**
     * Returns whether OS-level metrics are available.
     */
    public boolean hasOsMetrics() {
        return isLinux && Files.exists(Path.of("/proc/self/status"));
    }
}
