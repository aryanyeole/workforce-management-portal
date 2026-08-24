#!/usr/bin/env bash
# load/measure_approvals_keyset.sh
#
# Measures end-to-end HTTP latency for GET /api/v1/expenses/approvals at
# depths equivalent to measure_approvals_offset.sh's page 1/100/500/1000
# (size 20), using keyset pagination instead of page=N offset.
#
# "Page N" has no meaning for a cursor API — there is no page-N request to
# repeat. The equivalence used here: page N (size 20) covers the same rows
# as offset (N-1)*20. To reach that boundary with keyset, walk forward
# from the start consuming exactly (N-1)*20 rows (untimed setup, one time
# only, real HTTP requests through nextCursor) and keep the cursor sitting
# at that boundary. That cursor is then the fixed request measured —
# reused for every warmup and timed sample at that depth, exactly as the
# offset harness reuses the same page=N param for every sample at that
# depth. This measures "cost of one request at this depth", not "cost of
# traversing to this depth" — the walk is setup, not part of what's timed.
#
# The walk itself is done in chunks of SETUP_SIZE (the endpoint's max page
# size, 100) rather than in $SIZE-row steps: a cursor is just an opaque
# (submitted_at, id) marker for an exact row, so what page size was used
# to reach it is irrelevant to where it sits. Walking in bigger chunks
# only cuts the number of untimed setup requests (which would otherwise
# be 999 sequential requests for the deepest depth alone) — it changes
# nothing about the depths being measured or the $SIZE used for the
# actual timed request.
#
# depth    rows consumed   matches offset
# page1    0 (no walk)     offset 0
# page100  1980            offset 1980
# page500  9980            offset 9980
# page1000 19980           offset 19980
#
# Usage: ./measure_approvals_keyset.sh <base_url> <bearer_token> [samples] [warmup_per_depth] [size]
# Example: ./measure_approvals_keyset.sh http://localhost:8080 "$TOKEN" 100 20 20

set -euo pipefail

BASE_URL="${1:?base URL required}"
TOKEN="${2:?bearer token required}"
SAMPLES="${3:-100}"
WARMUP="${4:-20}"
SIZE="${5:-20}"
SETUP_SIZE=100

# label:rows-to-consume-during-setup-walk
DEPTHS=("page1:0" "page100:1980" "page500:9980" "page1000:19980")

fetch_next_cursor() {
  # $1 = current cursor (""  for none), $2 = page size to request.
  # Prints the response's nextCursor (or empty).
  local cursor="$1" size="$2" url
  if [ -z "$cursor" ]; then
    url="$BASE_URL/api/v1/expenses/approvals?size=$size"
  else
    url="$BASE_URL/api/v1/expenses/approvals?cursor=$cursor&size=$size"
  fi
  curl -s "$url" -H "Authorization: Bearer $TOKEN" | grep -o '"nextCursor":"[^"]*"' | cut -d'"' -f4
}

echo "== Setup: walking forward (untimed, $SETUP_SIZE rows/request) to establish each depth's boundary cursor ==" >&2
declare -A DEPTH_CURSOR
for PAIR in "${DEPTHS[@]}"; do
  LABEL="${PAIR%%:*}"
  ROWS="${PAIR##*:}"
  CURSOR=""
  REMAINING="$ROWS"
  while [ "$REMAINING" -gt 0 ]; do
    CHUNK=$SETUP_SIZE
    if [ "$REMAINING" -lt "$CHUNK" ]; then
      CHUNK="$REMAINING"
    fi
    CURSOR=$(fetch_next_cursor "$CURSOR" "$CHUNK")
    if [ -z "$CURSOR" ]; then
      echo "ERROR: result set exhausted while walking to $LABEL, $REMAINING rows short" >&2
      exit 1
    fi
    REMAINING=$((REMAINING - CHUNK))
  done
  DEPTH_CURSOR["$LABEL"]="$CURSOR"
  echo "  $LABEL -> cursor established after consuming $ROWS rows" >&2
done

echo "== Warmup: $WARMUP requests per depth, discarded ==" >&2
for PAIR in "${DEPTHS[@]}"; do
  LABEL="${PAIR%%:*}"
  CURSOR="${DEPTH_CURSOR[$LABEL]}"
  for i in $(seq 1 "$WARMUP"); do
    if [ -z "$CURSOR" ]; then
      curl -s -o /dev/null "$BASE_URL/api/v1/expenses/approvals?size=$SIZE" -H "Authorization: Bearer $TOKEN"
    else
      curl -s -o /dev/null "$BASE_URL/api/v1/expenses/approvals?cursor=$CURSOR&size=$SIZE" \
        -H "Authorization: Bearer $TOKEN"
    fi
  done
done
echo "Warmup complete ($(( WARMUP * ${#DEPTHS[@]} )) discarded requests)." >&2

# Build a randomized sequence of depth-indices (0..3), $SAMPLES of each,
# shuffled so depth order is not blocked/sequential — same rationale as
# measure_approvals_offset.sh.
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
  LABEL="${PAIR%%:*}"
  CURSOR="${DEPTH_CURSOR[$LABEL]}"
  if [ -z "$CURSOR" ]; then
    URL="$BASE_URL/api/v1/expenses/approvals?size=$SIZE"
  else
    URL="$BASE_URL/api/v1/expenses/approvals?cursor=$CURSOR&size=$SIZE"
  fi
  T=$(curl -s -o /dev/null -w "%{time_total}" "$URL" -H "Authorization: Bearer $TOKEN")
  echo "$T" >> "$RESULTS_DIR/$IDX"
done < "$SEQFILE"
rm -f "$SEQFILE"

echo "" >&2
echo "== Summary (p50/p95 = nearest-rank percentile over sorted samples) ==" >&2
for IDX in 0 1 2 3; do
  PAIR="${DEPTHS[$IDX]}"
  LABEL="${PAIR%%:*}"
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
