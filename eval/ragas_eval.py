"""
RAGAS sidecar for the Java RAG project.

Reads the per-mode datasets exported by RagMeasurementHarnessTest
(src/test/resources/measurement/ragas-dataset-<mode>.json) and scores each mode
with the official `ragas` library, then prints a cross-mode comparison table.

Metrics (all reference-free, since questions.json only has a weak keyword phrase,
not a full ground-truth answer):
  - faithfulness            : is the answer grounded in the retrieved contexts (anti-hallucination)
  - answer_relevancy        : does the answer actually address the question
  - context_precision       : are the relevant retrieved contexts ranked high (no reference variant)

context_recall is intentionally skipped — it needs a full reference answer we don't have.
Keep the Java harness's doc-level Hit@K as the honest retrieval-recall proxy.

The judge LLM + embeddings run against any OpenAI-compatible endpoint. Defaults target
SiliconFlow (which hosts the bge-reranker we already use). Override via env if needed.

Usage:
  pip install -r eval/requirements.txt
  export SILICONFLOW_API_KEY=sk-...           # your SiliconFlow key
  # optional overrides:
  #   export RAGAS_BASE_URL=https://api.siliconflow.cn/v1
  #   export RAGAS_LLM_MODEL=Qwen/Qwen2.5-7B-Instruct
  #   export RAGAS_EMBED_MODEL=BAAI/bge-m3
  python eval/ragas_eval.py

Writes eval/ragas-scores.json with the per-mode metric scores.
"""

import json
import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATASET_DIR = PROJECT_ROOT / "src" / "test" / "resources" / "measurement"
MODES = ["vector", "hybrid", "hybrid_rerank", "vector_rewrite"]


def load_samples(mode: str):
    path = DATASET_DIR / f"ragas-dataset-{mode}.json"
    if not path.exists():
        return None
    rows = json.loads(path.read_text(encoding="utf-8"))
    # Map the Java export schema -> ragas SingleTurnSample fields.
    return [
        {
            "user_input": r["question"],
            "retrieved_contexts": [c for c in (r.get("contexts") or []) if c],
            "response": r.get("answer") or "",
        }
        for r in rows
    ]


def build_evaluator():
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    api_key = os.environ.get("SILICONFLOW_API_KEY") or os.environ.get("LLM_RERANK_API_KEY")
    if not api_key:
        sys.exit("set SILICONFLOW_API_KEY (or LLM_RERANK_API_KEY) to your SiliconFlow key")
    base_url = os.environ.get("RAGAS_BASE_URL", "https://api.siliconflow.cn/v1")
    # A small judge (e.g. 7B) truncates ragas' strict-JSON output -> LLMDidNotFinishException.
    # Use a stronger judge and a generous token budget so the verdict JSON completes.
    llm_model = os.environ.get("RAGAS_LLM_MODEL", "Qwen/Qwen2.5-72B-Instruct")
    embed_model = os.environ.get("RAGAS_EMBED_MODEL", "BAAI/bge-m3")
    max_tokens = int(os.environ.get("RAGAS_LLM_MAX_TOKENS", "2048"))

    judge = ChatOpenAI(
        model=llm_model, api_key=api_key, base_url=base_url,
        temperature=0.0, max_tokens=max_tokens, timeout=120,
    )
    embeddings = OpenAIEmbeddings(model=embed_model, api_key=api_key, base_url=base_url)
    return (
        LangchainLLMWrapper(judge),
        LangchainEmbeddingsWrapper(embeddings),
        {"base_url": base_url, "llm_model": llm_model, "embed_model": embed_model},
    )


def main():
    from ragas import EvaluationDataset, evaluate
    from ragas.run_config import RunConfig
    from ragas.metrics import (
        Faithfulness,
        ResponseRelevancy,
        LLMContextPrecisionWithoutReference,
    )

    judge_llm, judge_embeddings, cfg = build_evaluator()
    print(f"[ragas] judge={cfg['llm_model']} embed={cfg['embed_model']} base={cfg['base_url']}\n")

    metrics = [
        Faithfulness(llm=judge_llm),
        ResponseRelevancy(llm=judge_llm, embeddings=judge_embeddings),
        LLMContextPrecisionWithoutReference(llm=judge_llm),
    ]

    # SiliconFlow shared endpoints rate-limit / time out under ragas' default 16-way
    # concurrency. Throttle and give each judge call room + retries.
    run_config = RunConfig(timeout=180, max_workers=4, max_retries=5)

    all_scores = {}
    for mode in MODES:
        samples = load_samples(mode)
        if not samples:
            print(f"[ragas] skip {mode}: dataset not found, run the Java harness first")
            continue
        print(f"[ragas] evaluating mode={mode} ({len(samples)} samples) ...")
        dataset = EvaluationDataset.from_list(samples)
        result = evaluate(
            dataset=dataset, metrics=metrics,
            llm=judge_llm, embeddings=judge_embeddings,
            run_config=run_config,
        )
        scores = {k: round(float(v), 4) for k, v in result._repr_dict.items()} \
            if hasattr(result, "_repr_dict") else dict(result)
        all_scores[mode] = scores
        print(f"[ragas] {mode}: {scores}\n")

    out = PROJECT_ROOT / "eval" / "ragas-scores.json"
    out.write_text(json.dumps(all_scores, ensure_ascii=False, indent=2), encoding="utf-8")

    # Comparison table
    if all_scores:
        metric_names = sorted({m for s in all_scores.values() for m in s})
        header = "| mode | " + " | ".join(metric_names) + " |"
        sep = "|---" * (len(metric_names) + 1) + "|"
        print("\n" + header)
        print(sep)
        for mode, scores in all_scores.items():
            cells = " | ".join(f"{scores.get(m, float('nan')):.4f}" for m in metric_names)
            print(f"| {mode} | {cells} |")
    print(f"\n[ragas] scores written to {out}")


if __name__ == "__main__":
    main()
