package com.example.benchmark;

import java.util.List;

/**
 * Renders ASCII art graphs for memory metrics visualization.
 */
public class AsciiGraphRenderer {

    private static final char[] GRAPH_CHARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private final int width;
    private final int height;

    /**
     * Creates a new renderer with specified dimensions.
     *
     * @param width  width of the graph in characters
     * @param height height of the graph in lines
     */
    public AsciiGraphRenderer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Creates a renderer with default dimensions (60x8).
     */
    public AsciiGraphRenderer() {
        this(60, 8);
    }

    /**
     * Renders a heap usage graph for the given benchmark result.
     *
     * @param result the benchmark result to render
     * @return multi-line string containing the ASCII graph
     */
    public String renderHeapGraph(BenchmarkResult result) {
        List<MemoryMetrics> samples = result.samples();
        if (samples.isEmpty()) {
            return "  (no samples)";
        }

        // Extract heap values in MB
        double[] values = samples.stream()
                .mapToDouble(MemoryMetrics::heapUsedMB)
                .toArray();

        return renderGraph("Heap Used (MB)", values, result.durationMs());
    }

    /**
     * Renders an RSS graph for the given benchmark result.
     *
     * @param result the benchmark result to render
     * @return multi-line string containing the ASCII graph, or empty if RSS unavailable
     */
    public String renderRssGraph(BenchmarkResult result) {
        List<MemoryMetrics> samples = result.samples();
        if (samples.isEmpty() || samples.get(0).rssBytes() < 0) {
            return "";
        }

        double[] values = samples.stream()
                .mapToDouble(MemoryMetrics::rssMB)
                .filter(v -> v > 0)
                .toArray();

        if (values.length == 0) {
            return "";
        }

        return renderGraph("RSS (MB)", values, result.durationMs());
    }

    /**
     * Renders a graph with the given title and values.
     */
    private String renderGraph(String title, double[] values, long durationMs) {
        StringBuilder sb = new StringBuilder();

        // Find min/max for scaling
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        // Add some padding to max
        double range = max - min;
        if (range < 1) {
            range = 1;
        }
        max = max + range * 0.1;
        min = Math.max(0, min - range * 0.1);
        range = max - min;

        // Downsample if we have more values than width
        double[] downsampled = downsample(values, width);

        // Title
        sb.append("  ").append(title).append("\n");

        // Y-axis labels and graph lines
        for (int row = height - 1; row >= 0; row--) {
            double rowMin = min + (range * row / height);
            double rowMax = min + (range * (row + 1) / height);

            // Y-axis label (only at top, middle, bottom)
            if (row == height - 1) {
                sb.append(String.format("%5.0f ", max));
            } else if (row == 0) {
                sb.append(String.format("%5.0f ", min));
            } else if (row == height / 2) {
                sb.append(String.format("%5.0f ", (min + max) / 2));
            } else {
                sb.append("      ");
            }

            // Graph line
            sb.append("│");
            for (double v : downsampled) {
                if (v >= rowMax) {
                    sb.append("█");
                } else if (v >= rowMin) {
                    // Partial fill
                    double fraction = (v - rowMin) / (rowMax - rowMin);
                    int charIndex = (int) (fraction * (GRAPH_CHARS.length - 1));
                    sb.append(GRAPH_CHARS[Math.min(charIndex, GRAPH_CHARS.length - 1)]);
                } else {
                    sb.append(" ");
                }
            }
            sb.append("\n");
        }

        // X-axis
        sb.append("      └");
        sb.append("─".repeat(width));
        sb.append("\n");

        // X-axis labels
        sb.append("       0s");
        double durationSec = durationMs / 1000.0;
        String endLabel = String.format("%.1fs", durationSec);
        int padding = width - 2 - endLabel.length();
        sb.append(" ".repeat(Math.max(1, padding)));
        sb.append(endLabel);
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Downsamples an array to fit within the target width.
     */
    private double[] downsample(double[] values, int targetWidth) {
        if (values.length <= targetWidth) {
            return values;
        }

        double[] result = new double[targetWidth];
        double step = (double) values.length / targetWidth;

        for (int i = 0; i < targetWidth; i++) {
            int start = (int) (i * step);
            int end = (int) ((i + 1) * step);
            end = Math.min(end, values.length);

            // Take max in this bucket to preserve peaks
            double max = values[start];
            for (int j = start + 1; j < end; j++) {
                max = Math.max(max, values[j]);
            }
            result[i] = max;
        }

        return result;
    }

    /**
     * Renders a compact sparkline for the given values.
     *
     * @param values the values to render
     * @return a single-line sparkline string
     */
    public String renderSparkline(double[] values) {
        if (values.length == 0) {
            return "";
        }

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        double range = max - min;
        if (range < 0.001) {
            range = 1;
        }

        StringBuilder sb = new StringBuilder();
        for (double v : values) {
            int index = (int) ((v - min) / range * (GRAPH_CHARS.length - 1));
            index = Math.max(0, Math.min(index, GRAPH_CHARS.length - 1));
            sb.append(GRAPH_CHARS[index]);
        }
        return sb.toString();
    }
}
