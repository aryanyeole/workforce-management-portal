#!/usr/bin/env bash
# load/generate_submit_traffic.sh
#
# Sustained CONCURRENT traffic against POST /api/v1/payroll/runs/{id}/submit,
# for Phase 8's pool-exhaustion reproduction.
#
# This is deliberately NOT built on measure_approvals_offset.sh/
# measure_approvals_keyset.sh's approach (Phase 6/7): those are
# single-threaded — one request in flight at a time, by design, because
# they exist to MEASURE latency at controlled depths without concurrency
# as a confound. Reproducing pool exhaustion needs the opposite: multiple
# requests genuinely in flight at once, so enough connections are held
# concurrently to actually starve a bounded pool. This spawns
# $CONCURRENCY background worker loops instead of one sequential one.
#
# Each worker repeatedly submits a randomly-chosen run id from
# RUN_IDS_FILE for the whole duration — including ids it (or another
# worker) already submitted, which correctly 409s. That's intentional:
# the point is sustained load against the endpoint, not a fixed number of
# successful transitions, and a 409 still needs the same DB round-trip a
# 200 does, so it still contends for a pool connection exactly the same
# way.
#
# Usage: ./generate_submit_traffic.sh <base_url> <token> <duration_seconds> <concurrency> <run_ids_file> <output_csv>
# Example: ./generate_submit_traffic.sh http://localhost:8080 "$TOKEN" 240 8 draft_run_ids.txt submit_traffic.csv

set -uo pipefail  # not -e: individual curl failures (expected once the pool is exhausted) must not abort the script

BASE_URL="${1:?base URL required}"
TOKEN="${2:?bearer token required}"
DURATION_SECONDS="${3:?duration in seconds required}"
CONCURRENCY="${4:?number of concurrent workers required}"
RUN_IDS_FILE="${5:?path to a file of run ids, one per line, required}"
OUTPUT_CSV="${6:?output CSV path required}"

mapfile -t RUN_IDS < "$RUN_IDS_FILE"
NUM_RUNS=${#RUN_IDS[@]}

END_EPOCH=$(( $(date +%s) + DURATION_SECONDS ))
WORKER_DIR=$(mktemp -d)

worker() {
  local worker_log="$1"
  while [ "$(date +%s)" -lt "$END_EPOCH" ]; do
    local run_id="${RUN_IDS[$((RANDOM % NUM_RUNS))]}"
    local ts
    ts=$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)
    local result
    result=$(curl -s -o /dev/null -w "%{http_code},%{time_total}" \
      -X POST "$BASE_URL/api/v1/payroll/runs/$run_id/submit" \
      -H "Authorization: Bearer $TOKEN" </dev/null)
    echo "$ts,$run_id,$result" >> "$worker_log"
  done
}

for w in $(seq 1 "$CONCURRENCY"); do
  worker "$WORKER_DIR/worker_$w.csv" &
done
wait

echo "timestamp,run_id,status_code,elapsed_seconds" > "$OUTPUT_CSV"
cat "$WORKER_DIR"/worker_*.csv 2>/dev/null | sort >> "$OUTPUT_CSV"
rm -rf "$WORKER_DIR"

echo "generate_submit_traffic.sh DONE: $(( $(wc -l < "$OUTPUT_CSV") - 1 )) requests recorded" >&2
