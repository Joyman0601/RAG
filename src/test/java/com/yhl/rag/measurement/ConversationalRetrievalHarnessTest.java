package com.yhl.rag.measurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.document.DocumentIngestTask;
import com.yhl.rag.document.DocumentIngestTaskService;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.document.DocumentUploadResponse;
import com.yhl.rag.llm.EmbeddingCache;
import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmGenerationResult;
import com.yhl.rag.llm.LlmMessage;
import com.yhl.rag.rag.ConversationHistory;
import com.yhl.rag.rag.ConversationTurn;
import com.yhl.rag.rag.QueryRewriterService;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.rag.RagSearchCache;
import com.yhl.rag.rag.RagSearchOutcome;
import com.yhl.rag.rag.RagSearchResult;
import com.yhl.rag.rag.RagSearchService;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * #7 多轮会话 RAG 量化 harness。默认关，仅 MEASUREMENT_RUN=true 时跑；需真实 LLM + embedding 端点。
 *
 * <p>用真实语料 + 「指代追问」对验证 conversational query rewrite 的检索增益：
 * 每个 case 先跑 turn-1（建立实体、自包含），把 (turn1, 真实答案) 写进会话历史；再对带指代的 turn-2
 * 比较两条检索路径的 turn-2 Hit@K：
 * <ul>
 *   <li><b>baseline</b>：turn-2 原样检索（指代未消解）；</li>
 *   <li><b>treatment</b>：用历史做 conversational rewrite，把指代消解成自包含 query 再检索。</li>
 * </ul>
 * 产出 conversational-measurement-report.md。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "llm.max-input-chars=6000",
        "rag.search.top-k=3",
        "rag.search.score-threshold=0.3",
        "rag.search.recall-top-k=50",
        "rag.chunk-size=600",
        "rag.chunk-overlap=100",
        "document.ingest.worker-fixed-delay-ms=3600000"
})
@EnabledIfEnvironmentVariable(named = "MEASUREMENT_RUN", matches = "true")
class ConversationalRetrievalHarnessTest {

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的 RAG 问答助手。
            你必须只基于用户提供的资料回答问题。
            如果资料中没有答案，回答“根据现有资料无法回答。”
            回答要简洁，不要编造资料外的信息。
            """;

    @Autowired private DocumentService documentService;
    @Autowired private DocumentIngestTaskService ingestTaskService;
    @Autowired private EmbeddingCache embeddingCache;
    @Autowired private LlmClient llmClient;
    @Autowired private RagProperties ragProperties;
    @Autowired private RagSearchCache ragSearchCache;
    @Autowired private RagSearchService ragSearchService;
    @Autowired private MockCurrentUserProvider currentUserProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private QueryRewriterService queryRewriterService;

    @Test
    void measure() throws Exception {
        List<RagMeasurementHarnessTest.CorpusDoc> corpus = objectMapper.readValue(
                readResource("measurement/corpus.json"), new TypeReference<>() {});
        List<Case> cases = objectMapper.readValue(
                readResource("measurement/conversational-questions.json"), new TypeReference<>() {});

        embeddingCache.clear();
        ragSearchCache.clear();

        // 入库真实语料（真实 embedding API）。
        Map<String, String> keyToDocumentId = new LinkedHashMap<>();
        for (RagMeasurementHarnessTest.CorpusDoc doc : corpus) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", doc.filename, "text/markdown", doc.content.getBytes(StandardCharsets.UTF_8));
            DocumentUploadResponse upload = documentService.upload(file);
            keyToDocumentId.put(doc.key, upload.getDocumentId());
            DocumentIngestTask task = ingestTaskService.getByDocumentId(upload.getDocumentId());
            if (ingestTaskService.markRunning(task.getTaskId())) {
                if (!documentService.processIngestTask(task)) {
                    throw new IllegalStateException("processIngestTask returned false for " + doc.key);
                }
                ingestTaskService.markSuccess(task.getTaskId());
            }
        }
        Map<String, String> documentIdToKey = new HashMap<>();
        keyToDocumentId.forEach((k, v) -> documentIdToKey.put(v, k));
        CurrentUser user = currentUserProvider.getCurrentUser();

        // 多轮改写开关（量化对照用），baseline 路径不经 rewriter，treatment 路径才用。
        ragProperties.getQueryRewrite().setEnabled(true);
        ragProperties.getQueryRewrite().getConversation().setEnabled(true);

        List<Row> rows = new ArrayList<>();
        for (Case c : cases) {
            // turn-1：真实跑一遍拿答案，作为会话历史里的助手回合。
            String answer1 = answer(c.turn1, user);
            ConversationHistory history = new ConversationHistory(
                    "", List.of(new ConversationTurn(c.turn1, answer1)));

            // baseline：turn-2 原样检索。
            ragSearchCache.clear();
            List<String> baseKeys = retrievedKeys(ragSearchService.searchWithMetrics(c.turn2, user, false), documentIdToKey);
            boolean baseHit = c.expectedKeys.stream().anyMatch(baseKeys::contains);

            // treatment：用历史做指代消解再检索。
            String rewritten = queryRewriterService.rewrite(c.turn2, history);
            ragSearchCache.clear();
            List<String> rwKeys = retrievedKeys(ragSearchService.searchWithMetrics(rewritten, user, false), documentIdToKey);
            boolean rwHit = c.expectedKeys.stream().anyMatch(rwKeys::contains);

            Row row = new Row();
            row.turn1 = c.turn1;
            row.turn2 = c.turn2;
            row.rewritten = rewritten;
            row.expectedKeys = c.expectedKeys;
            row.baselineKeys = baseKeys;
            row.baselineHit = baseHit;
            row.rewriteKeys = rwKeys;
            row.rewriteHit = rwHit;
            rows.add(row);
        }

        ragProperties.getQueryRewrite().getConversation().setEnabled(false);
        ragProperties.getQueryRewrite().setEnabled(false);

        int total = rows.size();
        long baseHits = rows.stream().filter(r -> r.baselineHit).count();
        long rwHits = rows.stream().filter(r -> r.rewriteHit).count();

        StringBuilder sb = new StringBuilder();
        sb.append("# 多轮会话 RAG 量化报告（#7）\n\n");
        sb.append("Generated: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
        sb.append("语料 ").append(corpus.size()).append(" 篇；指代追问对 ").append(total).append(" 组；top-K=")
                .append(ragProperties.getSearch().getTopK()).append("。\n\n");
        sb.append("## turn-2 检索 Hit@K：baseline（原样） vs treatment（指代消解）\n\n");
        sb.append("| 路径 | Hit@K | 命中数 |\n|---|---|---|\n");
        sb.append(String.format(Locale.ROOT, "| baseline（追问原样检索） | %.1f%% | %d/%d |\n",
                100.0 * baseHits / total, baseHits, total));
        sb.append(String.format(Locale.ROOT, "| treatment（会话改写后检索） | %.1f%% | %d/%d |\n\n",
                100.0 * rwHits / total, rwHits, total));

        sb.append("## 逐组明细\n\n");
        sb.append("| # | turn-1 | turn-2（指代） | 改写后 | 期望文档 | baseline命中 | 改写后命中 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        int i = 1;
        for (Row r : rows) {
            sb.append("| ").append(i++).append(" | ")
                    .append(esc(r.turn1)).append(" | ")
                    .append(esc(r.turn2)).append(" | ")
                    .append(esc(r.rewritten)).append(" | ")
                    .append(String.join(",", r.expectedKeys)).append(" | ")
                    .append(r.baselineHit ? "Y" : "N").append(" | ")
                    .append(r.rewriteHit ? "Y" : "N").append(" |\n");
        }
        sb.append("\n> baseline 命中的检索词是 turn-2 原话（含指代）；treatment 是会话改写后的自包含 query。\n");

        Path out = Paths.get("conversational-measurement-report.md");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("[measurement] conversational report written to " + out.toAbsolutePath());
        System.out.println(String.format(Locale.ROOT,
                "[measurement] turn-2 Hit@K  baseline=%.1f%%  treatment=%.1f%%  (n=%d)",
                100.0 * baseHits / total, 100.0 * rwHits / total, total));
    }

    private String answer(String question, CurrentUser user) {
        List<RagSearchResult> hits = ragSearchService.searchWithMetrics(question, user, false).results();
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            ctx.append("[").append(i + 1).append("]\n文件：").append(hits.get(i).getFilename())
                    .append("\n内容：").append(hits.get(i).getContent()).append("\n\n");
        }
        LlmGenerationResult r = llmClient.generateWithUsage(
                SYSTEM_PROMPT, List.of(new LlmMessage("user", "问题：\n" + question + "\n\n资料：\n" + ctx)));
        return r.getAnswer();
    }

    private static List<String> retrievedKeys(RagSearchOutcome outcome, Map<String, String> documentIdToKey) {
        return outcome.results().stream()
                .map(h -> documentIdToKey.getOrDefault(h.getDocumentId(), "?"))
                .toList();
    }

    private static byte[] readResource(String path) throws IOException {
        try (var in = ConversationalRetrievalHarnessTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("resource not found: " + path);
            }
            return in.readAllBytes();
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    public static class Case {
        public String turn1;
        public String turn2;
        public List<String> expectedKeys = List.of();
    }

    private static class Row {
        String turn1;
        String turn2;
        String rewritten;
        List<String> expectedKeys;
        List<String> baselineKeys;
        boolean baselineHit;
        List<String> rewriteKeys;
        boolean rewriteHit;
    }
}
