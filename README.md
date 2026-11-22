# StockPulse

**A scalable order and inventory service in Java 21**: PostgreSQL for the source of truth,
Redis for the cache, rate limiter and job queue, and a design where retries, crashes and
traffic spikes cannot lose an order, create a duplicate one, or oversell stock.

It is written on the JDK alone plus the PostgreSQL JDBC driver: the HTTP server, Redis client
(RESP protocol), connection pools, JSON codec and metrics are all in this repository, so every
moving part is visible and tested.

```
            POST /orders (Idempotency-Key)                      GET /products/{sku}
                     |                                                  |
              +------v-------+   token bucket (Lua, atomic)     +-------v--------+
   client --> |   API (HTTP, |<------------------------------- >|  Redis cache   |
              | virtual thr.)|                                  | (cache-aside,  |
              +------+-------+                                  |  TTL + delete) |
                     | one transaction:                         +----------------+
                     |  idempotency claim -> lock stock rows in SKU order ->
                     |  available -> reserved -> order + lines -> outbox row
              +------v-------+        +---------------+        +----------------+
              |  PostgreSQL  |------->| outbox relay  |------->|  Redis Stream  |
              |  (truth)     |  poll  | (SKIP LOCKED) |  XADD  |    "orders"    |
              +------^-------+        +---------------+        +-------+--------+
                     |                                    XREADGROUP  |  XACK after commit
                     |   settle: reserved -> sold, or back to available|  XPENDING/XCLAIM reclaim
                     +--------------------------------+----------------+  dead-letter after N tries
                                                      |
                                              +-------v-------+
                                              | queue workers | --> payment gateway (simulated)
                                              +---------------+
```

## What it guarantees, and how

| Guarantee | Mechanism | Proven by |
|---|---|---|
| A retried request never creates a second order | `Idempotency-Key` row claimed in the same transaction as the order; concurrent retries block on it and replay the stored response; a reused key with a different body gets 422 | `concurrentRetriesOfOneKeyCreateOneOrder` (24 simultaneous retries, 1 order); crash test E3 |
| Stock is never oversold | Rows locked `FOR UPDATE` and checked inside the transaction; `CHECK (available >= 0)` as a backstop | `neverOversellsUnderContention` (300 buyers, 100 units); flash sale E4 |
| Units are never lost or double-counted | Every unit is in exactly one bucket; `CHECK (available + reserved + sold = total)` | conservation checked after every experiment |
| No deadlocks between multi-item orders | Rows always locked in SKU order | `opposingMultiLineOrdersDoNotDeadlock` |
| An order is never stuck without its event | Transactional outbox: order and event commit together; relay publishes at least once | `relayRepublishIsAbsorbed` |
| A redelivered event is applied once | Status re-checked under a row lock, plus a primary key on (order, from_status) | `duplicateDeliveriesApplyOnce` (16 concurrent deliveries, 1 transition) |
| A dead worker's jobs are finished by another | Ack only after commit; live workers reclaim idle pending entries (XPENDING + XCLAIM) | `deadConsumersEntriesAreReclaimed`; crash test E3 |
| A poison message cannot block the queue | Moved to `orders-dlq` after N failed deliveries | `poisonEntryIsDeadLettered` |
| One client cannot starve others | Per-client token bucket in a Redis Lua script, using Redis server time, shared by all API instances; `429` + `Retry-After`; fails open if Redis is down | `RateLimitTest` |

## Measured results

From `bench/run.sh` on one 2-core machine (Intel Xeon 2.10 GHz, 7 GB RAM) running the load
generator, API, workers, PostgreSQL 16 and Redis 7 together. Full tables: [bench/results/RESULTS.md](bench/results/RESULTS.md).

| Experiment | Result |
|---|---|
| **Crash test**: 30 s of orders; `kill -9` the worker at 8 s and the API at 16 s | **9,152 orders, 0 duplicates, 0 lost, 0 stuck in the queue**, after 1,109 client retries with the same key; backlog drained 14.1 s after load |
| **Flash sale**: 64 clients, one SKU with 1,000 units | exactly **1,000 accepted, 2,175 rejected, 0 oversold** |
| **Order placement** (32 clients, with relay + 8 workers) | **409 orders/s**, p50 68.5 ms, p99 208 ms; all 9,387 settled with 0 conservation violations |
| **Product reads, Redis cache on vs off** (64 clients, Zipf-popular SKUs) | **90.4% hit ratio**; server time per read **1.67 ms vs 4.66 ms (-64%)**; only 9.6% of reads reach PostgreSQL |

On this machine the 2 shared cores, not the database, cap client-side throughput, so the
cache shows up as load removed from PostgreSQL and lower server-side time rather than as more
requests per second. Numbers on other hardware will differ; rerun `bench/run.sh` to measure.

## API

| Method and path | Purpose |
|---|---|
| `POST /orders` | Place an order. Headers: `X-Client-Id`, `Idempotency-Key`. Body: `{"lines":[{"sku":"TEA-1","qty":3}]}`. `201` with the order (status `PENDING`), `409 insufficient_stock`, `422 unknown_sku` / `idempotency_key_reused`, `429 rate_limited` |
| `GET /orders/{id}` | Order status (`PENDING`, `CONFIRMED`, `CANCELLED`). Another client's order is a `404`, so IDs do not leak |
| `GET /products/{sku}` | Product with displayed stock (cached; may lag by up to the TTL, never used to decide an order) |
| `POST /products`, `POST /products/{sku}/restock`, `GET /stats/inventory` | Admin (header `X-Admin-Token` when `ADMIN_TOKEN` is set) |
| `GET /health`, `GET /metrics` | Health of PostgreSQL and Redis; Prometheus metrics (request counts and latency histograms per route, cache hits, queue and relay counters) |

```bash
curl -XPOST localhost:8080/orders -H 'X-Client-Id: demo' -H 'Idempotency-Key: a1' \
     -d '{"lines":[{"sku":"TEA-1","qty":3}]}'
```

## Run it

Requirements: Java 21, PostgreSQL and Redis (tested on PostgreSQL 16 and Redis 7.0; the worker needs Redis 6.2+ for `XPENDING ... IDLE`).

```bash
./gradlew test                      # 38 unit + integration tests (need local PostgreSQL and Redis, see below)
./gradlew jar
MODE=all java -jar build/libs/stockpulse-1.0.0.jar      # API + relay + workers in one process
```

Or the full stack with two worker replicas: `docker compose up --build`.

Processes take a role with `MODE`: `api` (HTTP + outbox relay), `worker` (queue consumers),
`relay`, or `all`. Scale by running more `api` and `worker` processes against the same
PostgreSQL and Redis; nothing is held in process memory except caches.

Settings (environment): `PORT`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL`, `REDIS_HOST`,
`REDIS_PORT`, `REDIS_DB`, `CACHE_ENABLED`, `CACHE_TTL_SECONDS`, `RATE_CAPACITY`,
`RATE_REFILL_PER_SECOND`, `WORKER_THREADS`, `CLAIM_IDLE_MS`, `MAX_DELIVERIES`,
`PAYMENT_LATENCY_MS`, `PAYMENT_DECLINE_PERCENT`, `ADMIN_TOKEN`.

**Tests** run against a real PostgreSQL database `stockpulse_test` (user/password `sp`/`sp` by
default; override with `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`) and Redis database 15
(`TEST_REDIS_HOST`, `TEST_REDIS_PORT`). They drop and recreate the test schema each time.

**Benchmarks**: `./gradlew jar && bench/run.sh && python3 bench/report.py` (uses database
`stockpulse_bench` and Redis database 1).

## Design notes

* **Why reserve, then settle.** Placing an order only moves stock from `available` to
  `reserved`, which is fast and keeps the request path free of the slow payment call. A worker
  settles it later: `reserved -> sold` on success, `reserved -> available` on decline.
* **Why an outbox instead of publishing from the request.** Writing to PostgreSQL and Redis in
  one request cannot be atomic: a crash between them loses the event or publishes an order that
  was rolled back. The outbox row commits with the order; the relay publishes it afterwards.
* **Why at-least-once plus idempotent consumers.** Exactly-once delivery is not available across
  a database and a queue; exactly-once *effect* is, by making the consumer idempotent.
* **Why the payment call happens outside the transaction.** Holding a row lock while waiting on
  a remote service turns its latency into lock contention. The worker charges first (keyed by
  order id, so a retry is not a second charge), then applies the result in a short transaction.
* **Why virtual threads.** Each request is plain blocking code on its own virtual thread; the
  connection-pool semaphores, not thread counts, bound the load on PostgreSQL and Redis.

## Not done here

* The service has not been deployed to a cloud provider; all results are from local runs.
* The Docker image, `docker-compose.yml` and the GitHub Actions workflow are written but were
  not run in the sandbox where this was built (it had no access to Docker Hub or Maven Central).
  The tests ran there through a small offline stand-in for JUnit 5 (`tools/junit-shim/`);
  `./gradlew test` runs the same tests on the real JUnit Platform.
* Payments are simulated (fixed latency, deterministic declines).

MIT licence.
