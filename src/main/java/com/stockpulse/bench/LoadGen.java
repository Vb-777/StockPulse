package com.stockpulse.bench;

import com.stockpulse.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Closed-loop load generator: N virtual-thread clients, each sending its next request as soon as
 * the previous one returns, for a fixed duration after a warm-up.
 *
 * <pre>
 *   loadgen seed  --url U --skus 1000 --stock 1000000
 *   loadgen run   --url U --scenario read|order|mixed|flash --concurrency 64 --duration 20 [--warmup 5]
 * </pre>
 *
 * Product popularity follows a Zipf distribution (s = 1.1), so a few SKUs are hot, as in a real
 * catalogue. Order requests carry a unique Idempotency-Key; on a connection failure (server down)
 * the client retries the same request with the same key, so the run doubles as a check that
 * retries across a crash never create duplicate orders. Results are written as JSON.
 */
public final class LoadGen {
    private LoadGen() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("usage: loadgen seed|run --flag value ...");
        Map<String, String> a = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        String url = a.getOrDefault("url", "http://127.0.0.1:8080");
        switch (args[0]) {
            case "seed" -> seed(url, Integer.parseInt(a.getOrDefault("skus", "1000")),
                    Long.parseLong(a.getOrDefault("stock", "1000000")), a.get("admin-token"));
            case "run" -> {
                Map<String, Object> r = run(url, a.getOrDefault("scenario", "read"),
                        Integer.parseInt(a.getOrDefault("concurrency", "64")),
                        Integer.parseInt(a.getOrDefault("duration", "20")),
                        Integer.parseInt(a.getOrDefault("warmup", "5")),
                        Integer.parseInt(a.getOrDefault("skus", "1000")),
                        Long.parseLong(a.getOrDefault("seed", "42")));
                String json = Json.write(r);
                System.out.println(json);
                if (a.containsKey("out")) Files.writeString(Path.of(a.get("out")), json);
            }
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    static String sku(int i) { return String.format("SKU-%05d", i); }

    static void seed(String url, int skus, long stock, String adminToken) throws Exception {
        HttpClient http = client();
        for (int i = 0; i < skus; i++) {
            String body = Json.write(Map.of("sku", sku(i), "name", "Product " + i, "price_cents", 100L + i, "stock", stock));
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/products"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
            if (adminToken != null) b.header("X-Admin-Token", adminToken);
            int code = http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code != 201 && code != 409) throw new IllegalStateException("seed failed for " + sku(i) + ": " + code);
        }
        System.out.println("{\"seeded\":" + skus + "}");
    }

    static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** Zipf(s) sampler over [0, n) by inverse CDF on a precomputed table. */
    static final class Zipf {
        final double[] cdf;

        Zipf(int n, double s) {
            cdf = new double[n];
            double sum = 0;
            for (int i = 0; i < n; i++) { sum += 1 / Math.pow(i + 1, s); cdf[i] = sum; }
            for (int i = 0; i < n; i++) cdf[i] /= sum;
        }

        int next(SplittableRandom r) {
            int i = Arrays.binarySearch(cdf, r.nextDouble());
            return i >= 0 ? i : Math.min(cdf.length - 1, -i - 1);
        }
    }

    static Map<String, Object> run(String url, String scenario, int concurrency, int durationS, int warmupS,
                                   int skus, long seed) throws Exception {
        HttpClient http = client();
        Zipf zipf = new Zipf(skus, 1.1);
        long start = System.nanoTime();
        long measureFrom = start + warmupS * 1_000_000_000L;
        long end = measureFrom + durationS * 1_000_000_000L;
        List<long[]> perThread = new ArrayList<>();
        Map<String, LongAdder> statuses = new ConcurrentHashMap<>();
        LongAdder connErrors = new LongAdder();
        LongAdder retried = new LongAdder();
        AtomicLong keySeq = new AtomicLong();
        long runId = System.currentTimeMillis();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < concurrency; t++) {
            long[][] holder = new long[1][];
            final int tid = t;
            Thread th = Thread.ofVirtual().start(() -> {
                SplittableRandom rnd = new SplittableRandom(seed * 1_000_003 + tid);
                long[] lat = new long[1 << 16];
                int n = 0;
                String client = "load-" + (tid % 16);
                while (true) {
                    long now = System.nanoTime();
                    if (now >= end) break;
                    boolean isOrder = switch (scenario) {
                        case "order", "flash" -> true;
                        case "mixed" -> rnd.nextInt(10) == 0;
                        default -> false;
                    };
                    HttpRequest req;
                    if (isOrder) {
                        String s = scenario.equals("flash") ? sku(0) : sku(zipf.next(rnd));
                        String body = "{\"lines\":[{\"sku\":\"" + s + "\",\"qty\":1}]}";
                        req = HttpRequest.newBuilder(URI.create(url + "/orders")).timeout(Duration.ofSeconds(30))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .header("X-Client-Id", client)
                                .header("Idempotency-Key", runId + "-" + keySeq.incrementAndGet()).build();
                    } else {
                        req = HttpRequest.newBuilder(URI.create(url + "/products/" + sku(zipf.next(rnd))))
                                .timeout(Duration.ofSeconds(30)).GET().build();
                    }
                    long t0 = System.nanoTime();
                    int code = -1;
                    for (int attempt = 0; attempt < 100 && code < 0; attempt++) {
                        try {
                            code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                        } catch (IOException e) {
                            connErrors.increment();
                            if (!isOrder) break; // reads are not retried
                            retried.increment();
                            try { Thread.sleep(100); } catch (InterruptedException ie) { return; }
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    long t1 = System.nanoTime();
                    if (t0 >= measureFrom) {
                        if (n == lat.length) lat = Arrays.copyOf(lat, n * 2);
                        lat[n++] = (t1 - t0) / 1000; // microseconds
                        statuses.computeIfAbsent(Integer.toString(code), k -> new LongAdder()).increment();
                    }
                }
                holder[0] = Arrays.copyOf(lat, n);
                synchronized (perThread) { perThread.add(holder[0]); }
            });
            threads.add(th);
        }
        for (Thread th : threads) th.join();
        long total = perThread.stream().mapToLong(x -> x.length).sum();
        long[] all = new long[(int) total];
        int k = 0;
        for (long[] x : perThread) { System.arraycopy(x, 0, all, k, x.length); k += x.length; }
        Arrays.sort(all);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", scenario);
        r.put("concurrency", (long) concurrency);
        r.put("duration_s", (long) durationS);
        r.put("requests", total);
        r.put("throughput_rps", Math.round(total / (double) durationS));
        r.put("p50_ms", pct(all, 0.50));
        r.put("p95_ms", pct(all, 0.95));
        r.put("p99_ms", pct(all, 0.99));
        r.put("max_ms", all.length == 0 ? 0.0 : all[all.length - 1] / 1000.0);
        Map<String, Object> st = new TreeMap<>();
        statuses.forEach((c, v) -> st.put(c, v.sum()));
        r.put("status_counts", st);
        r.put("connection_errors", connErrors.sum());
        r.put("order_retries_same_key", retried.sum());
        return r;
    }

    static double pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return Math.round(sorted[Math.max(0, i)] / 10.0) / 100.0; // ms, 2 decimals
    }
}
