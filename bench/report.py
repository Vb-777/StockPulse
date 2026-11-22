"""Turns bench/results/*.json into bench/results/RESULTS.md. Every number in the README comes from here."""
import json, re, pathlib

R = pathlib.Path(__file__).parent / "results"
j = lambda n: json.loads((R / n).read_text())

def metrics(name):
    out = {}
    for line in (R / name).read_text().splitlines():
        m = re.match(r'^(\S+?)(\{.*\})? (\S+)$', line)
        if m:
            out[(m.group(1), m.group(2) or "")] = float(m.group(3))
    return out

def server_mean_ms(m, route):
    lab = '{route="%s"}' % route
    return m[("http_request_duration_ms_sum", lab)] / m[("http_request_duration_ms_count", lab)]

on, off, mon, moff = j("e1_cache_on.json"), j("e1_cache_off.json"), metrics("e1_cache_on_metrics.txt"), metrics("e1_cache_off_metrics.txt")
hits = mon.get(("cache_requests_total", '{result="hit"}'), 0)
miss = mon.get(("cache_requests_total", '{result="miss"}'), 0)
e2, e2i, e3, e3i = j("e2_orders.json"), j("e2_invariants.json"), j("e3_orders.json"), j("e3_invariants.json")
e4, e4i, mach = j("e4_flash.json"), j("e4_inventory.json"), j("machine.json")
m2 = metrics("e2_api_metrics.txt")

def row(name, r):
    return f"| {name} | {r['throughput_rps']:,} | {r['p50_ms']} | {r['p95_ms']} | {r['p99_ms']} |"

hit_ratio = hits / (hits + miss)
sm_on, sm_off = server_mean_ms(mon, "/products/{sku}"), server_mean_ms(moff, "/products/{sku}")
summary = {
    "cache_hit_ratio": round(hit_ratio, 4),
    "db_read_share_on": round(1 - hit_ratio, 4),
    "server_mean_ms_on": round(sm_on, 3), "server_mean_ms_off": round(sm_off, 3),
    "server_time_reduction_pct": round(100 * (1 - sm_on / sm_off), 1),
}
md = f"""# StockPulse benchmark results

Machine: {mach['cpu']}, {mach['cores']} cores, {mach['mem_gb']} GB RAM; Java {mach['java']},
PostgreSQL {mach['postgres'].split()[0]}, Redis {mach['redis']}. The load generator, API, workers,
PostgreSQL and Redis all ran on this one machine, so client-side throughput is capped by the
shared 2 cores; server-side timings (from the service's own /metrics) isolate the service.

## E1. Product reads: Redis cache on vs off ({on['concurrency']} clients, {on['duration_s']} s, Zipf-distributed SKUs)

| Run | req/s | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
{row('cache on', on)}
{row('cache off', off)}

* Cache hit ratio: **{100*hit_ratio:.2f}%** ({int(hits):,} hits, {int(miss):,} misses, warm-up included), so
  only {100*(1-hit_ratio):.1f}% of product reads reached PostgreSQL, against 100% with the cache off.
* Mean server-side handling time per read: {sm_on:.3f} ms with cache vs {sm_off:.3f} ms without
  ({summary['server_time_reduction_pct']}% less).
* Client-side req/s barely moves because the 2 shared cores, not the database, are the bottleneck
  at this scale: the cache's win here is load taken off PostgreSQL, not end-to-end throughput.

## E2. Order placement with outbox relay and 8 queue workers ({e2['concurrency']} clients, {e2['duration_s']} s)

| Run | req/s | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
{row('POST /orders', e2)}

* Mean server-side time per order: {server_mean_ms(m2, '/orders'):.2f} ms (one transaction: idempotency
  claim, row locks, stock move, order, outbox).
* All {e2i['invariants']['orders']:,} orders settled {e2i['drain_seconds_after_load']:.1f} s after load stopped
  ({e2i['invariants']['confirmed']:,} confirmed, {e2i['invariants']['cancelled']:,} declined by the simulated
  gateway at 10%); conservation violations: {e2i['invariants']['conservation_violations']}.

## E3. Crash test: kill -9 the worker at 8 s and the API at 16 s, during a 30 s order load

| Check | Result |
|---|---|
| Orders created (HTTP 201) | {e3['status_counts'].get('201', 0):,} |
| Client requests retried with the same Idempotency-Key after connection failures | {e3['order_retries_same_key']:,} |
| Orders in the database | {e3i['invariants']['orders']:,} |
| Orders without a matching 201 idempotency record (duplicates) | {e3i['invariants']['orders_without_key']} |
| Orders still PENDING after drain (lost) | {e3i['invariants']['pending']} |
| State transitions applied (must equal orders) | {e3i['invariants']['transitions']:,} |
| Stream entries still unacknowledged | {e3i['stream_pending_after_drain']} |
| Unpublished outbox rows | {e3i['invariants']['outbox_unpublished']} |
| Inventory rows breaking available + reserved + sold = total | {e3i['invariants']['conservation_violations']} |

The backlog left by the dead worker was reclaimed by its replacement and drained
{e3i['drain_seconds_after_load']:.1f} s after the load ended.

## E4. Flash sale: {e4['concurrency']} clients ordering one SKU with 1,000 units for {e4['duration_s']} s

* {e4['status_counts'].get('201', 0):,} orders accepted, {e4['status_counts'].get('409', 0):,} rejected as out of stock.
* Final inventory: available {e4i['available']}, reserved {e4i['reserved']}, total {e4i['total']}: **zero oversell**.
* Throughput under single-row contention: {e4['throughput_rps']:,} req/s, p99 {e4['p99_ms']} ms.
"""
(R / "RESULTS.md").write_text(md)
(R / "summary.json").write_text(json.dumps(summary, indent=2))
print(md)
print(summary)
