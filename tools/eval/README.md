# Eval fixtures — C-MTEB T2Retrieval

One-shot Python tool to download and sample C-MTEB T2Retrieval into BEIR-format
JSONL files that the Java `CmtebEvalRunner` can ingest. Output goes to
`target/eval-fixtures/cmteb-t2/` (gitignored).

## Setup (once)

```bash
python -m venv .venv
.venv\Scripts\activate          # Windows
# or
source .venv/bin/activate       # Unix
pip install -r requirements.txt
```

## Run

```bash
python fetch_cmteb.py --num-queries 300 --num-corpus 2000 --seed 42
```

Output:

```
target/eval-fixtures/cmteb-t2/
  corpus.jsonl     {"_id", "text"}
  queries.jsonl    {"_id", "text"}
  qrels.tsv        query-id\tcorpus-id\tscore (header on line 1)
  meta.json        sampling parameters + counts
```

## Sampling rule

We keep every relevant doc for the sampled queries (so Recall/nDCG are not
capped by the sample), then top up with random distractor docs to reach
`--num-corpus`. Default 300 queries × ~4 qrels/query = ~1200 relevant docs +
~800 distractors = 2000-doc corpus, big enough to make ranking non-trivial
without burning hours of embedding API calls per sweep group.

## Why a Python tool in a Java repo

`mteb` and `datasets` libraries handle HuggingFace download, BEIR-schema
quirks (`mteb/T2Retrieval` vs `C-MTEB/T2Retrieval-qrels` repo split), and
multi-lang dict shapes for free. This script runs once per fixture refresh;
the Java runner reads the static JSONL output.
