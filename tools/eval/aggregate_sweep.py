"""Aggregate latest cmteb-{label}-*.json reports under target/eval-reports/
into a single sweep-{ts}.{json,md} comparison table.

Picks the most recent JSON per label so re-running a group just supersedes
the previous attempt — no manual cleanup needed.
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

# Ordered: baseline first as anchor, then ablations
SWEEP_LABELS = ["baseline-v1", "chunk-256", "no-overlap", "no-rerank"]

REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_DIR = REPO_ROOT / "target" / "eval-reports"


def main() -> None:
    rows = []
    for label in SWEEP_LABELS:
        candidates = sorted(REPORT_DIR.glob(f"cmteb-{label}-*.json"), key=lambda p: p.stat().st_mtime)
        if not candidates:
            print(f"[skip] no report for label={label!r}")
            continue
        latest = candidates[-1]
        rows.append(_summarize(label, latest))
        print(f"[ok]   {label}: {latest.name}")

    if not rows:
        raise SystemExit("no reports found, did the sweep run?")

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    md_path = REPORT_DIR / f"cmteb-sweep-{ts}.md"
    json_path = REPORT_DIR / f"cmteb-sweep-{ts}.json"

    md_path.write_text(_render_markdown(rows), encoding="utf-8")
    json_path.write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")

    print()
    print(_render_markdown(rows))
    print()
    print(f"written: {md_path.relative_to(REPO_ROOT)}")
    print(f"written: {json_path.relative_to(REPO_ROOT)}")


def _summarize(label: str, path: Path) -> dict:
    r = json.loads(path.read_text(encoding="utf-8"))
    n_zero_ndcg = sum(1 for q in r["perQuery"] if q["ndcgAtTopK"] == 0.0 and not q["errored"])
    return {
        "label": label,
        "report": path.name,
        "chunkSize": r["chunkSize"],
        "chunkOverlap": r["chunkOverlap"],
        "retrievalMode": r["retrievalMode"],
        "chunksIngested": r["chunksIngested"],
        "ingestSeconds": round(r["ingestLatencyMs"] / 1000, 1),
        "scoredQueries": r["scoredQueries"],
        "erroredQueries": r["erroredQueries"],
        "zeroNdcgQueries": n_zero_ndcg,
        "ndcgAt10": round(r["meanNdcgAtTopK"], 4),
        "recallAt5": round(r["meanRecallAtRecallK"], 4),
        "mrrAt5": round(r["meanMrrAtRecallK"], 4),
        "hitAt5": round(r["hitRateAtRecallK"], 4),
        "p50Ms": int(r["p50RetrieveLatencyMs"]),
        "p90Ms": int(r["p90RetrieveLatencyMs"]),
        "p99Ms": int(r["p99RetrieveLatencyMs"]),
    }


def _render_markdown(rows: list[dict]) -> str:
    lines = []
    lines.append("# C-MTEB T2Retrieval — sweep summary")
    lines.append("")
    lines.append(f"_Generated: {datetime.now().isoformat(timespec='seconds')}_")
    lines.append("")
    lines.append("Fixture: 2000 corpus / 300 queries / ~1595 qrels (sampled with seed=42 from "
                 "C-MTEB T2Retrieval dev split). Embedding model + reranker fixed across groups; "
                 "only chunk size / overlap / rerank toggle vary. Low-confidence rejection "
                 "(`zhitu.rag.min-accepted-score`) disabled in sweep groups for pure retrieval signal.")
    lines.append("")
    lines.append("| label | chunk | ovlp | mode | nDCG@10 | Recall@5 | MRR@5 | Hit@5 | p50 ms | p90 ms | ingest s | chunks | err |")
    lines.append("|-------|-------|------|------|---------|----------|-------|-------|--------|--------|----------|--------|-----|")
    for r in rows:
        lines.append(
            f"| {r['label']} | {r['chunkSize']} | {r['chunkOverlap']} | {r['retrievalMode']} "
            f"| **{r['ndcgAt10']}** | {r['recallAt5']} | {r['mrrAt5']} | {r['hitAt5']} "
            f"| {r['p50Ms']} | {r['p90Ms']} | {r['ingestSeconds']} | {r['chunksIngested']} | {r['erroredQueries']} |"
        )
    lines.append("")
    if rows:
        baseline = next((r for r in rows if r["label"] == "baseline-v1"), rows[0])
        lines.append("## Δ vs baseline")
        lines.append("")
        lines.append("| label | ΔnDCG@10 | ΔRecall@5 | Δp50 ms |")
        lines.append("|-------|---------:|----------:|--------:|")
        for r in rows:
            d_ndcg = r["ndcgAt10"] - baseline["ndcgAt10"]
            d_recall = r["recallAt5"] - baseline["recallAt5"]
            d_p50 = r["p50Ms"] - baseline["p50Ms"]
            lines.append(
                f"| {r['label']} | {d_ndcg:+.4f} | {d_recall:+.4f} | {d_p50:+d} |"
            )
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
