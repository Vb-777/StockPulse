package com.stockpulse.metrics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counters and latency histograms, rendered in the Prometheus text exposition format. */
public final class Metrics {
    /** Histogram upper bounds in milliseconds. */
    static final double[] BOUNDS = {0.5, 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();

    static String key(String name, String... labels) {
        if (labels.length == 0) return name;
        StringBuilder b = new StringBuilder(name).append('{');
        for (int i = 0; i + 1 < labels.length; i += 2) {
            if (i > 0) b.append(',');
            b.append(labels[i]).append("=\"").append(labels[i + 1].replace("\"", "'")).append('"');
        }
        return b.append('}').toString();
    }

    public void inc(String name, String... labels) { add(1, name, labels); }

    public void add(long n, String name, String... labels) {
        counters.computeIfAbsent(key(name, labels), k -> new LongAdder()).add(n);
    }

    public long count(String name, String... labels) {
        LongAdder a = counters.get(key(name, labels));
        return a == null ? 0 : a.sum();
    }

    public void observeMs(double ms, String name, String... labels) {
        histograms.computeIfAbsent(key(name, labels), k -> new Histogram()).observe(ms);
    }

    public String render() {
        StringBuilder b = new StringBuilder();
        new TreeMap<>(counters).forEach((k, v) -> b.append(k).append(' ').append(v.sum()).append('\n'));
        new TreeMap<>(histograms).forEach((k, h) -> h.render(b, k));
        return b.toString();
    }

    static final class Histogram {
        final LongAdder[] buckets = new LongAdder[BOUNDS.length + 1];
        final LongAdder count = new LongAdder();
        final java.util.concurrent.atomic.DoubleAdder sum = new java.util.concurrent.atomic.DoubleAdder();

        Histogram() { for (int i = 0; i < buckets.length; i++) buckets[i] = new LongAdder(); }

        void observe(double ms) {
            int i = 0;
            while (i < BOUNDS.length && ms > BOUNDS[i]) i++;
            buckets[i].increment();
            count.increment();
            sum.add(ms);
        }

        void render(StringBuilder b, String key) {
            int brace = key.indexOf('{');
            String name = brace < 0 ? key : key.substring(0, brace);
            String labels = brace < 0 ? "" : key.substring(brace + 1, key.length() - 1);
            String sep = labels.isEmpty() ? "" : labels + ",";
            long cum = 0;
            for (int i = 0; i < buckets.length; i++) {
                cum += buckets[i].sum();
                String le = i < BOUNDS.length ? trim(BOUNDS[i]) : "+Inf";
                b.append(name).append("_bucket{").append(sep).append("le=\"").append(le).append("\"} ").append(cum).append('\n');
            }
            String suffix = labels.isEmpty() ? "" : "{" + labels + "}";
            b.append(name).append("_sum").append(suffix).append(' ').append(sum.sum()).append('\n');
            b.append(name).append("_count").append(suffix).append(' ').append(count.sum()).append('\n');
        }

        static String trim(double d) { return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d); }
    }
}
