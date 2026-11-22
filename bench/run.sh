#!/usr/bin/env bash
# Runs the four StockPulse experiments against local PostgreSQL and Redis and writes JSON
# results to bench/results/. Everything (load generator, API, workers, PostgreSQL, Redis)
# shares one machine, so absolute numbers depend on it; compare runs on the same machine.
#
#   ./gradlew jar && bench/run.sh
#
# Needs: psql, redis-cli, a PostgreSQL database reachable as $DB_URL, Redis on $REDIS_PORT.
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=${JAR:-$(ls build/libs/stockpulse-*.jar | head -1)}
OUT=bench/results
mkdir -p "$OUT" bench/logs
export DB_URL=${DB_URL:-jdbc:postgresql://127.0.0.1:5432/stockpulse_bench}
export DB_USER=${DB_USER:-sp} DB_PASSWORD=${DB_PASSWORD:-sp}
export REDIS_HOST=${REDIS_HOST:-127.0.0.1} REDIS_PORT=${REDIS_PORT:-6379} REDIS_DB=${REDIS_DB:-1}
export PGPASSWORD=$DB_PASSWORD
PSQL="psql -h 127.0.0.1 -U $DB_USER -d ${DB_URL##*/} -tAq"
PORT=8080
URL=http://127.0.0.1:$PORT
SKUS=1000
JAVA="java -Duser.timezone=UTC -Xms512m -Xmx1g"
PIDS=()

reset() {
  $PSQL -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" FLUSHDB >/dev/null
}

start() { # start <name> <env assignments...>
  local name=$1; shift
  env "$@" $JAVA -jar "$JAR" >"bench/logs/$name.log" 2>&1 &
  echo $!
}

wait_health() {
  for _ in $(seq 1 100); do curl -fs "$URL/health" >/dev/null 2>&1 && return 0; sleep 0.2; done
  echo "server did not become healthy" >&2; return 1
}

stop_all() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; wait 2>/dev/null || true; PIDS=(); }
trap stop_all EXIT

loadgen() { $JAVA -jar "$JAR" loadgen "$@"; }

wait_drained() { # waits until no PENDING orders remain; prints seconds waited
  local t0=$(date +%s.%N)
  for _ in $(seq 1 600); do
    [ "$($PSQL -c "SELECT count(*) FROM orders WHERE status='PENDING'")" = "0" ] && break
    sleep 0.1
  done
  echo "$(date +%s.%N) - $t0" | bc
}

invariants() { # JSON with the correctness checks
  $PSQL -c "SELECT json_build_object(
    'orders', (SELECT count(*) FROM orders),
    'pending', (SELECT count(*) FROM orders WHERE status='PENDING'),
    'confirmed', (SELECT count(*) FROM orders WHERE status='CONFIRMED'),
    'cancelled', (SELECT count(*) FROM orders WHERE status='CANCELLED'),
    'transitions', (SELECT count(*) FROM order_transitions),
    'idempotency_rows_201', (SELECT count(*) FROM idempotency_keys WHERE status_code=201),
    'orders_without_key', (SELECT count(*) FROM orders o WHERE NOT EXISTS (SELECT 1 FROM idempotency_keys k
         WHERE k.status_code=201 AND k.response_body::json->>'id' = o.id::text)),
    'conservation_violations', (SELECT count(*) FROM inventory WHERE available+reserved+sold<>total),
    'negative_stock_rows', (SELECT count(*) FROM inventory WHERE available<0 OR reserved<0),
    'outbox_unpublished', (SELECT count(*) FROM outbox WHERE published_at IS NULL))"
}

COMMON=(PORT=$PORT DB_POOL=16 RATE_CAPACITY=1000000 RATE_REFILL_PER_SECOND=1000000)
DUR=${DUR:-20}

# ---------------------------------------------------------------- E1: cache on vs off
echo "E1: product reads, cache on vs off"
reset
PIDS+=($(start e1-cache MODE=http CACHE_ENABLED=true CACHE_TTL_SECONDS=5 "${COMMON[@]}")); wait_health
loadgen seed --url $URL --skus $SKUS --stock 1000000 >/dev/null
loadgen run --url $URL --scenario read --concurrency 64 --duration $DUR --warmup 5 --skus $SKUS --out $OUT/e1_cache_on.json >/dev/null
curl -s $URL/metrics > $OUT/e1_cache_on_metrics.txt
stop_all
PIDS+=($(start e1-nocache MODE=http CACHE_ENABLED=false "${COMMON[@]}")); wait_health
loadgen run --url $URL --scenario read --concurrency 64 --duration $DUR --warmup 5 --skus $SKUS --out $OUT/e1_cache_off.json >/dev/null
curl -s $URL/metrics > $OUT/e1_cache_off_metrics.txt
stop_all

# ---------------------------------------------------------------- E2: order pipeline
echo "E2: order placement with relay + 8 queue workers"
reset
PIDS+=($(start e2-api MODE=api "${COMMON[@]}")); wait_health
PIDS+=($(start e2-worker MODE=worker WORKER_THREADS=8 PAYMENT_LATENCY_MS=20 PAYMENT_DECLINE_PERCENT=10 DB_POOL=16))
loadgen seed --url $URL --skus $SKUS --stock 1000000 >/dev/null
loadgen run --url $URL --scenario order --concurrency 32 --duration $DUR --warmup 5 --skus $SKUS --out $OUT/e2_orders.json >/dev/null
curl -s $URL/metrics > $OUT/e2_api_metrics.txt
echo "{\"drain_seconds_after_load\": $(wait_drained), \"invariants\": $(invariants)}" > $OUT/e2_invariants.json
stop_all

# ---------------------------------------------------------------- E3: kill -9 worker and API mid-load
echo "E3: crash test (kill -9 the worker at 8 s and the API at 16 s)"
reset
PIDS+=($(start e3-api MODE=api "${COMMON[@]}")); API=${PIDS[-1]}; wait_health
PIDS+=($(start e3-worker MODE=worker WORKER_THREADS=8 PAYMENT_LATENCY_MS=20 PAYMENT_DECLINE_PERCENT=10 CLAIM_IDLE_MS=3000 DB_POOL=16)); W1=${PIDS[-1]}
loadgen seed --url $URL --skus $SKUS --stock 1000000 >/dev/null
loadgen run --url $URL --scenario order --concurrency 32 --duration 30 --warmup 0 --skus $SKUS --out $OUT/e3_orders.json >/dev/null &
LG=$!
sleep 8;  kill -9 $W1; echo "  killed worker $W1"
sleep 3;  PIDS+=($(start e3-worker2 MODE=worker WORKER_THREADS=8 PAYMENT_LATENCY_MS=20 PAYMENT_DECLINE_PERCENT=10 CLAIM_IDLE_MS=3000 DB_POOL=16)); echo "  started replacement worker"
sleep 5;  kill -9 $API; echo "  killed API $API"
sleep 2;  PIDS+=($(start e3-api2 MODE=api "${COMMON[@]}")); wait_health; echo "  restarted API"
wait $LG
DRAIN=$(wait_drained)
PEL=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n "$REDIS_DB" XPENDING orders workers | head -1)
echo "{\"drain_seconds_after_load\": $DRAIN, \"stream_pending_after_drain\": $PEL, \"invariants\": $(invariants)}" > $OUT/e3_invariants.json
stop_all

# ---------------------------------------------------------------- E4: flash sale on one SKU
echo "E4: flash sale, 64 clients on one SKU with 1,000 units"
reset
PIDS+=($(start e4-api MODE=api "${COMMON[@]}")); wait_health
loadgen seed --url $URL --skus 1 --stock 1000 >/dev/null
loadgen run --url $URL --scenario flash --concurrency 64 --duration 10 --warmup 0 --skus 1 --out $OUT/e4_flash.json >/dev/null
$PSQL -c "SELECT json_build_object('available',available,'reserved',reserved,'sold',sold,'total',total,
  'orders',(SELECT count(*) FROM orders)) FROM inventory WHERE sku='SKU-00000'" > $OUT/e4_inventory.json
stop_all

echo "{\"cpu\": \"$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs)\", \"cores\": $(nproc), \"mem_gb\": $(free -g | awk '/Mem/{print $2}'), \"java\": \"$(java -version 2>&1 | grep -m1 version | cut -d'"' -f2)\", \"postgres\": \"$($PSQL -c 'SHOW server_version')\", \"redis\": \"$(redis-cli -p $REDIS_PORT INFO server | grep redis_version | cut -d: -f2 | tr -d '\r')\"}" > $OUT/machine.json
echo "done: results in $OUT/"
