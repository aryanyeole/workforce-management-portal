#!/usr/bin/env bash
# load/measure_approvals_offset.sh
#
# Measures end-to-end HTTP latency for GET /api/v1/expenses/approvals at
# page depths 1/100/500/1000 (size 20), using classic page=N-1 offset
# pagination.
#
# Fixes over the first (superseded) Phase 6 Task 2 measurement, whose HTTP
# p50 figures *decreased* with page depth — a result with no plausible
# mechanism, pointing at JIT/connection-pool warmup confounding the
# sequential "run all of page 1, then all of page 100, ..." order:
#   - a discarded warmup phase runs first, so JIT/connection-pool effects
#     are absorbed before any timed sample
#   - timed requests are issued in a RANDOMIZED order across depths (not
#     grouped by depth), so warmup/thermal drift over the run can't
#     correlate with depth
#   - reports p50 AND p95, with sample size, not just a mean/median from
#     an unstated number of samples
#
# Usage: ./measure_approvals_offset.sh <base_url> <bearer_token> [samples] [warmup_per_depth]
# Example: ./measure_approvals_offset.sh http://localhost:8080 "$TOKEN" 100 20

set -euo pipefail

BASE_URL="${1:?base URL required}"
TOKEN="${2:?bearer token required}"
SAMPLES="${3:-100}"
WARMUP="${4:-20}"

# label:page-param (page is 0-indexed; page 1 => param 0, etc.)
DEPTHS=("1:0" "100:99" "500:499" "1000:999")

echo "== Warmup: $WARMUP requests per depth, discarded ==" >&2
for PAIR in "${DEPTHS[@]}"; do
  PAGEPARAM="${PAIR##*:}"
  for i in $(seq 1 "$WARMUP"); do
    curl -s -o /dev/null "$BASE_URL/api/v1/expenses/approvals?page=$PAGEPARAM&size=20" \
      -H "Authorization: Bearer $TOKEN"
  done
done
echo "Warmup complete ($(( WARMUP * ${#DEPTHS[@]} )) discarded requests)." >&2

# Build a randomized sequence of depth-indices (0..3), $SAMPLES of each,
# shuffled so depth order is not blocked/sequential.
SEQFILE=$(mktemp)
for pass in $(seq 1 "$SAMPLES"); do
  for idx in 0 1 2 3; do
    echo "$idx"
  done
done | shuf > "$SEQFILE"

RESULTS_DIR=$(mktemp -d)
echo "== Measuring: $SAMPLES samples per depth, randomized order across depths ==" >&2
while read -r IDX; do
  PAIR="${DEPTHS[$IDX]}"
  PAGEPARAM="${PAIR##*:}"
  T=$(curl -s -o /dev/null -w "%{time_total}" "$BASE_URL/api/v1/expenses/approvals?page=$PAGEPARAM&size=20" \
    -H "Authorization: Bearer $TOKEN")
  echo "$T" >> "$RESULTS_DIR/$IDX"
done < "$SEQFILE"
rm -f "$SEQFILE"

echo "" >&2
echo "== Summary (p50/p95 = nearest-rank percentile over sorted samples) ==" >&2
for IDX in 0 1 2 3; do
  PAIR="${DEPTHS[$IDX]}"
  LABEL="page${PAIR%%:*}"
  sort -n "$RESULTS_DIR/$IDX" | awk -v label="$LABEL" '
    { a[NR] = $1 }
    END {
      n = NR
      sum = 0
      for (i = 1; i <= n; i++) sum += a[i]
      p50i = int(0.50 * n); if (p50i < 0.50 * n) p50i++; if (p50i < 1) p50i = 1; if (p50i > n) p50i = n
      p95i = int(0.95 * n); if (p95i < 0.95 * n) p95i++; if (p95i < 1) p95i = 1; if (p95i > n) p95i = n
      printf "%-9s n=%-4d min=%.4f p50=%.4f p95=%.4f max=%.4f mean=%.4f\n", label, n, a[1], a[p50i], a[p95i], a[n], sum/n
    }'
done
rm -rf "$RESULTS_DIR"
