"""Sample C-MTEB T2Retrieval into BEIR-format fixtures for the Java eval runner.

Output layout (under --output-dir):
  corpus.jsonl   {"_id": str, "text": str}
  queries.jsonl  {"_id": str, "text": str}
  qrels.tsv      query-id\tcorpus-id\tscore  (header on first line)
  meta.json      {dataset, num_queries, num_corpus, num_qrels, seed, ...}

Sampling rule: keep every relevant doc for the chosen queries (so Recall/nDCG
are not capped by the sample), then top up with random distractor docs until
we reach --num-corpus.

Targets mteb >= 2.0 dataset shape: t.dataset['default'][split] = {corpus, queries, relevant_docs}.
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

import mteb


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--task", default="T2Retrieval")
    parser.add_argument("--split", default="dev")
    parser.add_argument("--config", default="default", help="mteb 2.x config key (usually 'default')")
    parser.add_argument("--num-queries", type=int, default=300)
    parser.add_argument("--num-corpus", type=int, default=2000)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--output-dir",
        default="target/eval-fixtures/cmteb-t2",
        help="relative to repo root or absolute",
    )
    args = parser.parse_args()

    rng = random.Random(args.seed)

    print(f"loading mteb task {args.task}...", flush=True)
    task = mteb.get_task(args.task)
    task.load_data()

    data = task.dataset[args.config][args.split]
    corpus_ds = data["corpus"]
    queries_ds = data["queries"]
    qrels_root = data["relevant_docs"]

    corpus_root = {row["id"]: row for row in corpus_ds}
    queries_root = {row["id"]: row["text"] for row in queries_ds}

    print(
        f"loaded: corpus={len(corpus_root)} queries={len(queries_root)} qrels-queries={len(qrels_root)}",
        flush=True,
    )

    eligible_qids = sorted(qid for qid in qrels_root if qid in queries_root)
    if len(eligible_qids) < args.num_queries:
        raise SystemExit(
            f"only {len(eligible_qids)} eligible queries, less than --num-queries={args.num_queries}"
        )
    sampled_qids = rng.sample(eligible_qids, args.num_queries)

    needed_doc_ids: set[str] = set()
    sampled_qrels: list[tuple[str, str, float]] = []
    for qid in sampled_qids:
        for doc_id, score in qrels_root[qid].items():
            if doc_id not in corpus_root:
                continue
            needed_doc_ids.add(doc_id)
            sampled_qrels.append((qid, doc_id, float(score)))

    if len(needed_doc_ids) > args.num_corpus:
        raise SystemExit(
            f"relevant docs ({len(needed_doc_ids)}) exceed --num-corpus={args.num_corpus}; "
            f"raise --num-corpus or lower --num-queries"
        )

    distractor_pool = [d for d in corpus_root if d not in needed_doc_ids]
    rng.shuffle(distractor_pool)
    distractors = distractor_pool[: args.num_corpus - len(needed_doc_ids)]
    sampled_doc_ids = list(needed_doc_ids) + distractors
    rng.shuffle(sampled_doc_ids)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    _write_jsonl(
        out_dir / "corpus.jsonl",
        ({"_id": d, "text": _doc_text(corpus_root[d])} for d in sampled_doc_ids),
    )
    _write_jsonl(
        out_dir / "queries.jsonl",
        ({"_id": q, "text": queries_root[q]} for q in sampled_qids),
    )
    _write_qrels(out_dir / "qrels.tsv", sampled_qrels)

    meta = {
        "task": args.task,
        "config": args.config,
        "split": args.split,
        "seed": args.seed,
        "num_queries": len(sampled_qids),
        "num_corpus": len(sampled_doc_ids),
        "num_qrels": len(sampled_qrels),
        "num_relevant_docs": len(needed_doc_ids),
        "num_distractor_docs": len(distractors),
        "avg_qrels_per_query": round(len(sampled_qrels) / len(sampled_qids), 2),
    }
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(meta, indent=2, ensure_ascii=False))


def _doc_text(doc: dict) -> str:
    title = (doc.get("title") or "").strip()
    text = (doc.get("text") or "").strip()
    if title and text:
        return f"{title}\n{text}"
    return title or text


def _write_jsonl(path: Path, rows) -> None:
    with path.open("w", encoding="utf-8") as fp:
        for row in rows:
            fp.write(json.dumps(row, ensure_ascii=False))
            fp.write("\n")


def _write_qrels(path: Path, rows) -> None:
    with path.open("w", encoding="utf-8") as fp:
        fp.write("query-id\tcorpus-id\tscore\n")
        for qid, doc_id, score in rows:
            score_str = str(int(score)) if float(score).is_integer() else f"{score:.4f}"
            fp.write(f"{qid}\t{doc_id}\t{score_str}\n")


if __name__ == "__main__":
    main()
