# StockPulse benchmark results

Machine: Intel(R) Xeon(R) Processor @ 2.10GHz, 2 cores, 7 GB RAM; Java 21.0.12.1,
PostgreSQL 16.15, Redis 7.0.15. The load generator, API, workers,
PostgreSQL and Redis all ran on this one machine, so client-side throughput is capped by the
shared 2 cores; server-side timings (from the service's own /metrics) isolate the service.

## E1. Product reads: Redis cache on vs off (64 clients, 20 s, Zipf-distributed SKUs)

| Run | req/s | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
| cache on | 1,397 | 44.17 | 51.88 | 57.17 |
| cache off | 1,329 | 45.84 | 60.22 | 77.91 |

* Cache hit ratio: **90.42%** (30,037 hits, 3,181 misses, warm-up included), so
  only 9.6% of product reads reached PostgreSQL, against 100% with the cache off.
* Mean server-side handling time per read: 1.669 ms with cache vs 4.659 ms without
  (64.2% less).
* Client-side req/s barely moves because the 2 shared cores, not the database, are the bottleneck
  at this scale: the cache's win here is load taken off PostgreSQL, not end-to-end throughput.

## E2. Order placement with outbox relay and 8 queue workers (32 clients, 20 s)

| Run | req/s | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|
| POST /orders | 409 | 68.5 | 133.76 | 207.99 |

* Mean server-side time per order: 36.54 ms (one transaction: idempotency
  claim, row locks, stock move, order, outbox).
* All 9,387 orders settled 14.6 s after load stopped
  (8,422 confirmed, 965 declined by the simulated
  gateway at 10%); conservation violations: 0.

## E3. Crash test: kill -9 the worker at 8 s and the API at 16 s, during a 30 s order load

| Check | Result |
|---|---|
| Orders created (HTTP 201) | 9,152 |
| Client requests retried with the same Idempotency-Key after connection failures | 1,109 |
| Orders in the database | 9,152 |
| Orders without a matching 201 idempotency record (duplicates) | 0 |
| Orders still PENDING after drain (lost) | 0 |
| State transitions applied (must equal orders) | 9,152 |
| Stream entries still unacknowledged | 0 |
| Unpublished outbox rows | 0 |
| Inventory rows breaking available + reserved + sold = total | 0 |

The backlog left by the dead worker was reclaimed by its replacement and drained
14.1 s after the load ended.

## E4. Flash sale: 64 clients ordering one SKU with 1,000 units for 10 s

* 1,000 orders accepted, 2,175 rejected as out of stock.
* Final inventory: available 0, reserved 1000, total 1000: **zero oversell**.
* Throughput under single-row contention: 318 req/s, p99 871.54 ms.
