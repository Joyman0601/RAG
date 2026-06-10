@echo off
set MEASUREMENT_RUN=true
set LLM_API_KEY=cr_2bc03c24223bb6f9147e10afbbe8a9eb3f0e97a30353f0179631db0d33b4f44c
set LLM_EMBEDDING_BASE_URL=https://api.siliconflow.cn
set LLM_EMBEDDING_API_KEY=sk-wmoozsdjkvzlzwsgiafipejyzceanrhkxhzxnryljlmsrqxn
set LLM_EMBEDDING_MODEL=Qwen/Qwen3-VL-Embedding-8B
cd /d E:\yhl\RAG
mvn -DfailIfNoTests=false -Dtest=RagMeasurementHarnessTest -Dsurefire.failIfNoSpecifiedTests=false test
