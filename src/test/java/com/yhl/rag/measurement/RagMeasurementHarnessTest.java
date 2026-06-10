package com.yhl.rag.measurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentIngestTask;
import com.yhl.rag.document.DocumentIngestTaskService;
import com.yhl.rag.document.DocumentIngestTaskStatus;
import com.yhl.rag.document.DocumentInfo;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.document.DocumentStatus;
import com.yhl.rag.document.DocumentUploadResponse;
import com.yhl.rag.llm.EmbeddingCache;
import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.rag.RagSearchCache;
import com.yhl.rag.rag.RagSearchOutcome;
import com.yhl.rag.rag.RagSearchResult;
import com.yhl.rag.rag.RagSearchService;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Real-data measurement harness. Disabled by default; only runs when MEASUREMENT_RUN=true.
 * Requires real LLM_API_KEY, LLM_EMBEDDING_BASE_URL, LLM_EMBEDDING_API_KEY in the environment.
 * For hybrid_rerank mode also set LLM_RERANK_API_KEY.
 *
 * <p>Runs the same 26-question set through three retrieval modes (vector / hybrid / hybrid_rerank)
 * by routing through {@link RagSearchService} (the production retrieval path), and writes:
 * <ul>
 *   <li>measurement-report.md / .json — cross-mode comparison (Hit@K, relevance@K, latency)</li>
 *   <li>src/test/resources/measurement/ragas-dataset-&lt;mode&gt;.json — per-mode dataset for the
 *       Python RAGAS sidecar (eval/ragas_eval.py)</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        // The default 2000 cap can clip top-3 contexts; widen for measurement only.
        "llm.max-input-chars=6000",
        "rag.search.top-k=3",
        "rag.search.score-threshold=0.3",
        "rag.search.recall-top-k=50",
        "rag.chunk-size=600",
        "rag.chunk-overlap=100",
        // Park the @Scheduled IngestWorker so the harness owns task processing.
        "document.ingest.worker-fixed-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "MEASUREMENT_RUN", matches = "true")
class RagMeasurementHarnessTest {

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的 RAG 问答助手。
            你必须只基于用户提供的资料回答问题。
            如果资料中没有答案，回答“根据现有资料无法回答。”
            回答要简洁，不要编造资料外的信息。
            """;

    private static final List<RagProperties.RetrievalMode> MODES = List.of(
            RagProperties.RetrievalMode.VECTOR,
            RagProperties.RetrievalMode.HYBRID,
            RagProperties.RetrievalMode.HYBRID_RERANK
    );

    @Autowired private DocumentService documentService;
    @Autowired private DocumentIngestTaskService ingestTaskService;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private EmbeddingCache embeddingCache;
    @Autowired private LlmClient llmClient;
    @Autowired private LlmProperties llmProperties;
    @Autowired private RagProperties ragProperties;
    @Autowired private RagSearchCache ragSearchCache;
    @Autowired private RagSearchService ragSearchService;
    @Autowired private MockCurrentUserProvider currentUserProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.yhl.rag.rag.QueryRewriterService queryRewriterService;

    @Test
    void measure() throws Exception {
        // === 1. Load corpus + questions ==============================================
        List<CorpusDoc> corpus = objectMapper.readValue(
                readResource("measurement/corpus.json"), new TypeReference<>() {});
        List<Question> questions = objectMapper.readValue(
                readResource("measurement/questions.json"), new TypeReference<>() {});

        embeddingCache.clear();
        ragSearchCache.clear();

        // === 2. Ingest corpus (real embedding API) ===================================
        Map<String, String> keyToDocumentId = new LinkedHashMap<>();
        long ingestStart = System.nanoTime();
        int totalCharsAccepted = 0;
        for (CorpusDoc doc : corpus) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", doc.filename, "text/markdown", doc.content.getBytes(StandardCharsets.UTF_8));
            DocumentUploadResponse upload = documentService.upload(file);
            keyToDocumentId.put(doc.key, upload.getDocumentId());
            totalCharsAccepted += doc.content.length();

            DocumentIngestTask task = ingestTaskService.getByDocumentId(upload.getDocumentId());
            if (ingestTaskService.markRunning(task.getTaskId())) {
                if (!documentService.processIngestTask(task)) {
                    throw new IllegalStateException("processIngestTask returned false for " + doc.key);
                }
                ingestTaskService.markSuccess(task.getTaskId());
            } else {
                waitForDocumentReady(upload.getDocumentId());
            }
        }
        long ingestMs = Duration.ofNanos(System.nanoTime() - ingestStart).toMillis();

        // === 3. Corpus stats ========================================================
        List<DocumentChunk> allChunks = documentService.listAllChunks().stream()
                .filter(c -> c.getStatus() == DocumentStatus.ACTIVE)
                .toList();
        int chunkCount = allChunks.size();
        int vectorDimension = documentService.getChunkEmbeddingsSnapshot().values().stream()
                .filter(v -> v != null && !v.isEmpty())
                .mapToInt(List::size)
                .findFirst().orElse(0);

        Map<String, String> documentIdToKey = new HashMap<>();
        keyToDocumentId.forEach((k, v) -> documentIdToKey.put(v, k));
        CurrentUser user = currentUserProvider.getCurrentUser();

        // === 4. Embedding cache micro-bench (once, mode-independent) =================
        // Cold pass = SHA-256 cache miss (real HTTP); warm pass = cache hit (ConcurrentHashMap).
        embeddingCache.clear();
        List<Long> embedColdMsList = new ArrayList<>();
        List<Long> embedWarmMsList = new ArrayList<>();
        for (Question q : questions) {
            long t0 = System.nanoTime();
            embeddingClient.embed(q.question);
            embedColdMsList.add(ms(t0));
            t0 = System.nanoTime();
            embeddingClient.embed(q.question);
            embedWarmMsList.add(ms(t0));
        }

        // === 5. Per-mode evaluation through RagSearchService ========================
        List<ModeReport> modeReports = new ArrayList<>();
        for (RagProperties.RetrievalMode mode : MODES) {
            ragProperties.getSearch().setMode(mode);
            // Fresh per mode so each pays its own retrieval cost and hybrid bypasses stale cache.
            embeddingCache.clear();
            ragSearchCache.clear();

            List<PerQuestion> perQuestion = new ArrayList<>();
            List<RagasSample> ragasSamples = new ArrayList<>();
            for (Question q : questions) {
                long t0 = System.nanoTime();
                RagSearchOutcome outcome = ragSearchService.searchWithMetrics(q.question, user, false);
                long retrievalMs = ms(t0);
                List<RagSearchResult> hits = outcome.results();

                String context = buildContext(hits);
                String userPrompt = "问题：\n" + q.question + "\n\n资料：\n" + context;
                t0 = System.nanoTime();
                LlmGenerationResult result = llmClient.generateWithUsage(
                        SYSTEM_PROMPT, List.of(new LlmMessage("user", userPrompt)));
                long chatMs = ms(t0);

                List<String> retrievedKeys = hits.stream()
                        .map(h -> documentIdToKey.getOrDefault(h.getDocumentId(), "?"))
                        .toList();
                boolean docHit = q.expectedKeys.stream().anyMatch(retrievedKeys::contains);
                long relevantCount = retrievedKeys.stream().filter(q.expectedKeys::contains).count();
                double relevanceAtK = hits.isEmpty() ? 0.0 : (double) relevantCount / hits.size();
                boolean answerContainsExpected = q.expectedPhrase == null
                        || q.expectedPhrase.isBlank()
                        || (result.getAnswer() != null && result.getAnswer().contains(q.expectedPhrase));

                PerQuestion row = new PerQuestion();
                row.question = q.question;
                row.expectedKeys = q.expectedKeys;
                row.retrievedKeys = retrievedKeys;
                row.scores = hits.stream().map(RagSearchResult::getScore).toList();
                row.retrievalMs = retrievalMs;
                row.embeddingMs = outcome.embeddingDurationMs();
                row.searchMs = outcome.searchDurationMs();
                row.chatMs = chatMs;
                row.totalMs = retrievalMs + chatMs;
                row.docHit = docHit;
                row.relevanceAtK = relevanceAtK;
                row.answerContainsExpected = answerContainsExpected;
                row.promptTokens = result.getPromptTokens();
                row.completionTokens = result.getCompletionTokens();
                row.answer = result.getAnswer();
                perQuestion.add(row);

                RagasSample sample = new RagasSample();
                sample.question = q.question;
                sample.contexts = hits.stream().map(RagSearchResult::getContent).toList();
                sample.answer = result.getAnswer();
                sample.reference = q.expectedPhrase; // weak reference (keyword), not a full ground-truth answer
                sample.expectedKeys = q.expectedKeys;
                sample.retrievedKeys = retrievedKeys;
                sample.docHit = docHit;
                ragasSamples.add(sample);
            }

            ModeReport mr = new ModeReport();
            mr.mode = mode.name().toLowerCase(Locale.ROOT);
            mr.questionCount = perQuestion.size();
            mr.docHitCount = (int) perQuestion.stream().filter(p -> p.docHit).count();
            mr.docHitRate = perQuestion.isEmpty() ? 0.0 : (double) mr.docHitCount / perQuestion.size();
            mr.averageRelevanceAtK = perQuestion.stream().mapToDouble(p -> p.relevanceAtK).average().orElse(0.0);
            mr.answerPhraseHitRate = perQuestion.isEmpty() ? 0.0
                    : (double) perQuestion.stream().filter(p -> p.answerContainsExpected).count() / perQuestion.size();
            mr.retrievalMsList = perQuestion.stream().map(p -> p.retrievalMs).toList();
            mr.chatMsList = perQuestion.stream().map(p -> p.chatMs).toList();
            mr.totalMsList = perQuestion.stream().map(p -> p.totalMs).toList();
            mr.perQuestion = perQuestion;
            modeReports.add(mr);

            // Export per-mode RAGAS dataset.
            Path dataset = Paths.get("src/test/resources/measurement/ragas-dataset-" + mr.mode + ".json");
            Files.writeString(dataset,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ragasSamples),
                    StandardCharsets.UTF_8);
            System.out.println("[measurement] ragas dataset written to " + dataset.toAbsolutePath());
        }

        // === 5b. vector_rewrite mode: VECTOR retrieval with LLM query rewrite ========
        {
            ragProperties.getSearch().setMode(RagProperties.RetrievalMode.VECTOR);
            ragProperties.getQueryRewrite().setEnabled(true);
            embeddingCache.clear();
            ragSearchCache.clear();

            List<PerQuestion> perQuestion = new ArrayList<>();
            List<RagasSample> ragasSamples = new ArrayList<>();
            for (Question q : questions) {
                String rewrittenQuestion = queryRewriterService.rewrite(q.question);
                long t0 = System.nanoTime();
                RagSearchOutcome outcome = ragSearchService.searchWithMetrics(rewrittenQuestion, user, false);
                long retrievalMs = ms(t0);
                List<RagSearchResult> hits = outcome.results();

                String context = buildContext(hits);
                // 作答仍用原始 question，改写只作用于检索
                String userPrompt = "问题：\n" + q.question + "\n\n资料：\n" + context;
                t0 = System.nanoTime();
                LlmGenerationResult result = llmClient.generateWithUsage(
                        SYSTEM_PROMPT, List.of(new LlmMessage("user", userPrompt)));
                long chatMs = ms(t0);

                List<String> retrievedKeys = hits.stream()
                        .map(h -> documentIdToKey.getOrDefault(h.getDocumentId(), "?"))
                        .toList();
                boolean docHit = q.expectedKeys.stream().anyMatch(retrievedKeys::contains);
                long relevantCount = retrievedKeys.stream().filter(q.expectedKeys::contains).count();
                double relevanceAtK = hits.isEmpty() ? 0.0 : (double) relevantCount / hits.size();
                boolean answerContainsExpected = q.expectedPhrase == null
                        || q.expectedPhrase.isBlank()
                        || (result.getAnswer() != null && result.getAnswer().contains(q.expectedPhrase));

                PerQuestion row = new PerQuestion();
                row.question = q.question;
                row.expectedKeys = q.expectedKeys;
                row.retrievedKeys = retrievedKeys;
                row.scores = hits.stream().map(RagSearchResult::getScore).toList();
                row.retrievalMs = retrievalMs;
                row.embeddingMs = outcome.embeddingDurationMs();
                row.searchMs = outcome.searchDurationMs();
                row.chatMs = chatMs;
                row.totalMs = retrievalMs + chatMs;
                row.docHit = docHit;
                row.relevanceAtK = relevanceAtK;
                row.answerContainsExpected = answerContainsExpected;
                row.promptTokens = result.getPromptTokens();
                row.completionTokens = result.getCompletionTokens();
                row.answer = result.getAnswer();
                perQuestion.add(row);

                RagasSample sample = new RagasSample();
                sample.question = q.question;
                sample.contexts = hits.stream().map(RagSearchResult::getContent).toList();
                sample.answer = result.getAnswer();
                sample.reference = q.expectedPhrase;
                sample.expectedKeys = q.expectedKeys;
                sample.retrievedKeys = retrievedKeys;
                sample.docHit = docHit;
                ragasSamples.add(sample);
            }

            ModeReport mr = new ModeReport();
            mr.mode = "vector_rewrite";
            mr.questionCount = perQuestion.size();
            mr.docHitCount = (int) perQuestion.stream().filter(p -> p.docHit).count();
            mr.docHitRate = perQuestion.isEmpty() ? 0.0 : (double) mr.docHitCount / perQuestion.size();
            mr.averageRelevanceAtK = perQuestion.stream().mapToDouble(p -> p.relevanceAtK).average().orElse(0.0);
            mr.answerPhraseHitRate = perQuestion.isEmpty() ? 0.0
                    : (double) perQuestion.stream().filter(p -> p.answerContainsExpected).count() / perQuestion.size();
            mr.retrievalMsList = perQuestion.stream().map(p -> p.retrievalMs).toList();
            mr.chatMsList = perQuestion.stream().map(p -> p.chatMs).toList();
            mr.totalMsList = perQuestion.stream().map(p -> p.totalMs).toList();
            mr.perQuestion = perQuestion;
            modeReports.add(mr);

            Path dataset = Paths.get("src/test/resources/measurement/ragas-dataset-vector_rewrite.json");
            Files.writeString(dataset,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(ragasSamples),
                    StandardCharsets.UTF_8);
            System.out.println("[measurement] ragas dataset written to " + dataset.toAbsolutePath());

            ragProperties.getQueryRewrite().setEnabled(false);
        }
        Report report = new Report();
        report.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        report.documentCount = corpus.size();
        report.chunkCount = chunkCount;
        report.totalCorpusChars = totalCharsAccepted;
        report.vectorDimension = vectorDimension;
        report.embeddingModel = llmProperties.getEmbeddingModel();
        report.chatModel = llmProperties.getModel();
        report.rerankModel = llmProperties.getRerankModel();
        report.topK = ragProperties.getSearch().getTopK();
        report.recallTopK = ragProperties.getSearch().getRecallTopK();
        report.scoreThreshold = ragProperties.getSearch().getScoreThreshold();
        report.chunkSize = ragProperties.getChunkSize();
        report.chunkOverlap = ragProperties.getChunkOverlap();
        report.ingestTotalMs = ingestMs;
        report.embedColdMsList = embedColdMsList;
        report.embedWarmMsList = embedWarmMsList;
        report.modeReports = modeReports;

        Files.writeString(Paths.get("measurement-report.md"), renderMarkdown(report), StandardCharsets.UTF_8);
        Files.writeString(Paths.get("measurement-report.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        System.out.println("[measurement] report written to " + Paths.get("measurement-report.md").toAbsolutePath());
    }

    private void waitForDocumentReady(String documentId) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            DocumentInfo info = documentService.getDocumentInfoSnapshot().get(documentId);
            if (info != null && info.getStatus() == DocumentStatus.READY) {
                return;
            }
            DocumentIngestTask task = ingestTaskService.getByDocumentId(documentId);
            if (task != null && task.getStatus() == DocumentIngestTaskStatus.FAILED) {
                throw new IllegalStateException("ingest task failed for " + documentId
                        + ": " + task.getErrorCode() + " / " + task.getErrorMessage());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for document " + documentId);
            }
        }
        throw new IllegalStateException("document " + documentId + " not ready after 60s");
    }

    // ---- helpers ---------------------------------------------------------------

    private static long ms(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String buildContext(List<RagSearchResult> hits) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            RagSearchResult c = hits.get(i);
            sb.append("[").append(i + 1).append("]\n");
            sb.append("文件：").append(c.getFilename()).append("\n");
            sb.append("内容：").append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private static byte[] readResource(String classpathPath) throws IOException {
        try (var in = RagMeasurementHarnessTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IOException("resource not found: " + classpathPath);
            }
            return in.readAllBytes();
        }
    }

    private static double avg(List<Long> xs) {
        return xs.isEmpty() ? 0.0 : xs.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private static long p(List<Long> xs, double q) {
        if (xs.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        int idx = (int) Math.min(sorted.size() - 1, Math.round(q * (sorted.size() - 1)));
        return sorted.get(idx);
    }

    private static String renderMarkdown(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG measurement report\n\n");
        sb.append("Generated: ").append(r.timestamp).append("\n\n");

        sb.append("## Corpus & index\n\n");
        sb.append("| metric | value |\n|---|---|\n");
        sb.append(row("documents ingested", r.documentCount));
        sb.append(row("chunks indexed", r.chunkCount));
        sb.append(row("total corpus chars", r.totalCorpusChars));
        sb.append(row("vector dimension", r.vectorDimension));
        sb.append(row("embedding model", r.embeddingModel));
        sb.append(row("chat model", r.chatModel));
        sb.append(row("rerank model", r.rerankModel));
        sb.append(row("chunk size / overlap", r.chunkSize + " / " + r.chunkOverlap));
        sb.append(row("top-K / recall-top-K", r.topK + " / " + r.recallTopK));
        sb.append(row("score threshold", r.scoreThreshold));
        sb.append(row("ingest wall time (ms)", r.ingestTotalMs));
        sb.append("\n");

        sb.append("## Embedding cache effect (SHA-256 keyed, mode-independent)\n\n");
        double coldAvg = avg(r.embedColdMsList);
        double warmAvg = avg(r.embedWarmMsList);
        double reduction = coldAvg == 0 ? 0 : (coldAvg - warmAvg) / coldAvg * 100.0;
        sb.append("| pass | avg(ms) | p50(ms) | p95(ms) |\n|---|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| cold (miss) | %.1f | %d | %d |\n",
                coldAvg, p(r.embedColdMsList, 0.5), p(r.embedColdMsList, 0.95)));
        sb.append(String.format(Locale.ROOT, "| warm (hit) | %.1f | %d | %d |\n",
                warmAvg, p(r.embedWarmMsList, 0.5), p(r.embedWarmMsList, 0.95)));
        sb.append(String.format(Locale.ROOT,
                "\nReduction in average embedding latency: **%.1f%%** (cold %.1f ms -> warm %.1f ms)\n\n",
                reduction, coldAvg, warmAvg));

        sb.append("## Cross-mode retrieval comparison (").append(
                r.modeReports.isEmpty() ? 0 : r.modeReports.get(0).questionCount).append(" questions)\n\n");
        sb.append("| mode | Hit@K | relevance@K | answer-phrase | hit/total | retrieval p50(ms) | e2e p50(ms) | e2e p95(ms) |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (ModeReport m : r.modeReports) {
            sb.append(String.format(Locale.ROOT,
                    "| %s | %.1f%% | %.1f%% | %.1f%% | %d/%d | %d | %d | %d |\n",
                    m.mode, m.docHitRate * 100, m.averageRelevanceAtK * 100, m.answerPhraseHitRate * 100,
                    m.docHitCount, m.questionCount,
                    p(m.retrievalMsList, 0.5), p(m.totalMsList, 0.5), p(m.totalMsList, 0.95)));
        }
        sb.append("\n");

        for (ModeReport m : r.modeReports) {
            sb.append("## Per-question detail — ").append(m.mode).append("\n\n");
            sb.append("| # | question | hit? | relevance@K | retrieval | LLM | total |\n");
            sb.append("|---|---|---|---|---|---|---|\n");
            int i = 1;
            for (PerQuestion p : m.perQuestion) {
                sb.append("| ").append(i++).append(" | ")
                        .append(escape(p.question)).append(" | ")
                        .append(p.docHit ? "Y" : "N").append(" | ")
                        .append(String.format(Locale.ROOT, "%.2f", p.relevanceAtK)).append(" | ")
                        .append(p.retrievalMs).append(" ms | ")
                        .append(p.chatMs).append(" ms | ")
                        .append(p.totalMs).append(" ms |\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String row(String k, Object v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    // ---- DTOs ------------------------------------------------------------------

    public static class CorpusDoc {
        public String key;
        public String filename;
        public String content;
    }

    public static class Question {
        public String question;
        public List<String> expectedKeys = List.of();
        public String expectedPhrase;
    }

    public static class PerQuestion {
        public String question;
        public List<String> expectedKeys;
        public List<String> retrievedKeys;
        public List<Double> scores;
        public long retrievalMs;
        public long embeddingMs;
        public long searchMs;
        public long chatMs;
        public long totalMs;
        public boolean docHit;
        public double relevanceAtK;
        public boolean answerContainsExpected;
        public Integer promptTokens;
        public Integer completionTokens;
        public String answer;
    }

    /** One row exported for the Python RAGAS sidecar. */
    public static class RagasSample {
        public String question;
        public List<String> contexts;
        public String answer;
        public String reference;
        public List<String> expectedKeys;
        public List<String> retrievedKeys;
        public boolean docHit;
    }

    public static class ModeReport {
        public String mode;
        public int questionCount;
        public int docHitCount;
        public double docHitRate;
        public double averageRelevanceAtK;
        public double answerPhraseHitRate;
        public List<Long> retrievalMsList;
        public List<Long> chatMsList;
        public List<Long> totalMsList;
        public List<PerQuestion> perQuestion;
    }

    public static class Report {
        public String timestamp;
        public int documentCount;
        public int chunkCount;
        public int totalCorpusChars;
        public int vectorDimension;
        public String embeddingModel;
        public String chatModel;
        public String rerankModel;
        public int topK;
        public int recallTopK;
        public double scoreThreshold;
        public int chunkSize;
        public int chunkOverlap;
        public long ingestTotalMs;
        public List<Long> embedColdMsList;
        public List<Long> embedWarmMsList;
        public List<ModeReport> modeReports;
    }
}
