#!/usr/bin/env bash
# Run the 4-group C-MTEB sweep (baseline already exists, this script runs the 3 new groups).
# Each group launches a fresh boot, ingests into an isolated ES index, runs eval, exits.
# All groups disable low-confidence rejection (--zhitu.rag.min-accepted-score=0.0)
# so the metrics reflect pure retrieval quality, not RAG-mode safety filtering.
#
# Output:
#   target/eval-reports/cmteb-{label}-{ts}.json   (one per group)
#   logs/cmteb-{label}.log                         (full boot+eval log)
#
# Usage: bash scripts/cmteb-sweep.sh
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p logs target/eval-reports

run_group() {
  local label=$1 chunk=$2 overlap=$3 mode=$4 idx=$5
  echo ""
  echo "============================================================"
  echo "[$(date +%H:%M:%S)] group=$label chunk=$chunk overlap=$overlap mode=$mode idx=$idx"
  echo "============================================================"
  mvn -o spring-boot:run -Dspring-boot.run.profiles=local \
    -Dspring-boot.run.arguments="--server.port=0 \
--zhitu.eval.exit-after-run=true \
--zhitu.eval.cmteb.enabled=true \
--zhitu.eval.cmteb.label=$label \
--zhitu.eval.cmteb.chunk-size=$chunk \
--zhitu.eval.cmteb.chunk-overlap=$overlap \
--zhitu.eval.cmteb.top-k=10 \
--zhitu.eval.cmteb.recall-k=5 \
--zhitu.eval.cmteb.retrieval-mode=$mode \
--zhitu.elasticsearch.index-name=$idx \
--zhitu.rerank.final-top-k=50 \
--zhitu.rag.min-accepted-score=0.0" \
    2>&1 | tee "logs/cmteb-$label.log"
}

run_group chunk-256  256 64 hybrid-rerank zhitu_agent_eval_cmteb_c256_o64
run_group no-overlap 512  0 hybrid-rerank zhitu_agent_eval_cmteb_c512_o0
run_group no-rerank  512 64 hybrid        zhitu_agent_eval_cmteb_c512_o64_norer

echo ""
echo "[$(date +%H:%M:%S)] sweep done. Run aggregator:"
echo "  D:/dev/my_proj/java/zhitu-agent-java/tools/eval/.venv/Scripts/python.exe tools/eval/aggregate_sweep.py"
