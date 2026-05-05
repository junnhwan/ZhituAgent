#!/usr/bin/env bash
# scripts/perf-bench-upload.sh
#
# M5 micro-benchmark: M2 sync upload vs M3 async upload latency comparison.
#
# Sends N concurrent file uploads to a running zhitu-agent-java instance, in
# two passes: first against a sync-mode boot (kafka-enabled=false), then
# against an async-mode boot (kafka-enabled=true). For each pass it measures
# per-request controller latency (Tika + embed + ES.bulk inline for sync; just
# the MinIO put + Kafka send for async) and prints p50/p90 latency.
#
# Usage:
#   scripts/perf-bench-upload.sh \
#     --base-url=http://localhost:8080 \
#     --file=docs/m2-smoke-sample.txt \
#     --requests=50 \
#     --concurrency=10
#
# Pre-flight (operator):
#   1. Boot once with ZHITU_KAFKA_ENABLED=false; run this script with --label=sync
#   2. Stop, boot again with ZHITU_KAFKA_ENABLED=true; run again with --label=async
#   3. Compare the printed p50/p90 between runs.
#
# The expected M3 win is a controller latency drop from O(seconds) to <200ms
# because the async path returns 202 immediately; the consumer drives Tika +
# embedding + ES bulkIndex on its own thread pool. Throughput improvement is
# orthogonal — see the consumer-side trace logs to verify the indexing rate.

set -euo pipefail

BASE_URL="http://localhost:8080"
FILE="docs/m2-smoke-sample.txt"
REQUESTS=50
CONCURRENCY=10
LABEL="run"

for arg in "$@"; do
  case "$arg" in
    --base-url=*)    BASE_URL="${arg#*=}" ;;
    --file=*)        FILE="${arg#*=}" ;;
    --requests=*)    REQUESTS="${arg#*=}" ;;
    --concurrency=*) CONCURRENCY="${arg#*=}" ;;
    --label=*)       LABEL="${arg#*=}" ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$FILE" ]]; then
  echo "fixture not found: $FILE" >&2
  exit 1
fi

OUT_DIR="target/perf-bench"
mkdir -p "$OUT_DIR"
LATENCY_FILE="$OUT_DIR/latency-${LABEL}.txt"
: > "$LATENCY_FILE"

echo "[$LABEL] running $REQUESTS uploads with concurrency=$CONCURRENCY against $BASE_URL"

upload_one() {
  local idx="$1"
  local url="$BASE_URL/api/files/upload"
  # %{time_total} = total seconds since curl invoked; multiply to ms below.
  curl -s -o /dev/null \
       -w '%{http_code} %{time_total}\n' \
       -F "file=@${FILE};filename=bench-${LABEL}-${idx}.txt" \
       "$url"
}

export -f upload_one
export BASE_URL FILE LABEL

seq 1 "$REQUESTS" | xargs -n1 -P "$CONCURRENCY" -I{} bash -c 'upload_one "$@"' _ {} >> "$LATENCY_FILE"

echo "[$LABEL] raw latencies appended to $LATENCY_FILE"

# Summarize p50/p90 from raw curl timings.
python3 - "$LATENCY_FILE" "$LABEL" <<'PY'
import sys, statistics
path, label = sys.argv[1], sys.argv[2]
times_ms = []
fails = 0
with open(path) as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 2:
            fails += 1
            continue
        code, t = parts
        if not code.startswith(("2", "3")):
            fails += 1
            continue
        times_ms.append(float(t) * 1000.0)
times_ms.sort()
if not times_ms:
    print(f"[{label}] no successful responses (failures={fails})")
    sys.exit(1)
def pct(p): return times_ms[max(0, min(len(times_ms)-1, int(round(p/100.0 * (len(times_ms)-1)))))]
print(f"[{label}] n={len(times_ms)} fails={fails} "
      f"p50={pct(50):.1f}ms p90={pct(90):.1f}ms p99={pct(99):.1f}ms "
      f"avg={statistics.mean(times_ms):.1f}ms max={max(times_ms):.1f}ms")
PY
