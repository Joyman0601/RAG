import os, json
from pathlib import Path
os.environ.setdefault("RAGAS_LLM_MODEL","Qwen/Qwen2.5-72B-Instruct")
import ragas_eval as R
rows = R.load_samples("vector")[:3]
from ragas import EvaluationDataset, evaluate
from ragas.run_config import RunConfig
from ragas.metrics import Faithfulness, ResponseRelevancy, LLMContextPrecisionWithoutReference
llm, emb, cfg = R.build_evaluator()
print("judge:", cfg["llm_model"])
ds = EvaluationDataset.from_list(rows)
res = evaluate(dataset=ds, metrics=[Faithfulness(llm=llm), ResponseRelevancy(llm=llm, embeddings=emb), LLMContextPrecisionWithoutReference(llm=llm)],
               llm=llm, embeddings=emb, run_config=RunConfig(timeout=180, max_workers=3, max_retries=5))
print("SMOKE_RESULT:", dict(res._repr_dict) if hasattr(res,"_repr_dict") else dict(res))
