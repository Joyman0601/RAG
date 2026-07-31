package com.yhl.rag.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RagEvalService {

    private static final Logger log = LoggerFactory.getLogger(RagEvalService.class);
    private static final String NO_ANSWER = "根据现有资料无法回答。";

    private final RagSearchService ragSearchService;
    private final RagAskService ragAskService;
    private final ObjectMapper objectMapper;
    private final List<RagEvalCase> builtInCases = List.of(
            new RagEvalCase(
                    "leave-annual-days",
                    "入职满 3 年年假有多少天？",
                    "10 天",
                    List.of(),
                    List.of("01-年假与请假制度.md"),
                    null,
                    "user_001",
                    List.of("leave")
            ),
            new RagEvalCase(
                    "leave-sick-materials",
                    "病假需要提交什么材料？",
                    "二级及以上医院证明",
                    List.of(),
                    List.of("01-年假与请假制度.md"),
                    null,
                    "user_001",
                    List.of("leave")
            ),
            new RagEvalCase(
                    "reimbursement-hotel-tier1",
                    "一线城市出差住宿标准是多少？",
                    "600 元",
                    List.of(),
                    List.of("02-差旅报销制度.md"),
                    null,
                    "user_001",
                    List.of("reimbursement")
            ),
            new RagEvalCase(
                    "reimbursement-deadline",
                    "差旅报销的时限是多少天？",
                    "15 个工作日",
                    List.of(),
                    List.of("02-差旅报销制度.md"),
                    null,
                    "user_001",
                    List.of("reimbursement")
            ),
            new RagEvalCase(
                    "order-cancel-paid",
                    "PAID 状态的订单可以取消吗？",
                    "HITL 人工确认",
                    List.of(),
                    List.of("03-订单管理FAQ.md"),
                    null,
                    "user_001",
                    List.of("order")
            ),
            new RagEvalCase(
                    "offboarding-notice-period",
                    "正式员工离职需要提前多久提出？",
                    "30 日",
                    List.of(),
                    List.of("04-离职与知识产权.md"),
                    null,
                    "user_001",
                    List.of("offboarding")
            ),
            new RagEvalCase(
                    "remote-core-hours",
                    "远程办公的核心工作时间是几点到几点？",
                    "10:00-16:00",
                    List.of(),
                    List.of("05-远程办公政策.md"),
                    null,
                    "user_001",
                    List.of("remote")
            )
    );

    public RagEvalService(RagSearchService ragSearchService, RagAskService ragAskService, ObjectMapper objectMapper) {
        this.ragSearchService = ragSearchService;
        this.ragAskService = ragAskService;
        this.objectMapper = objectMapper;
    }

    public RagEvalResponse evaluate(boolean onlySearch) {
        return evaluate(new RagEvalRunRequest() {{
            setOnlySearch(onlySearch);
        }});
    }

    public RagEvalResponse evaluate(RagEvalRunRequest request) {
        RagEvalRunRequest safeRequest = request == null ? new RagEvalRunRequest() : request;
        List<RagEvalCase> cases = loadCases(safeRequest.getCaseFile());
        List<RagEvalResult> results = cases.stream()
                .map(evalCase -> evaluateCaseSafely(evalCase, safeRequest.isOnlySearch()))
                .toList();
        RagEvalSummary summary = summarize(results);

        log.info("rag_eval_run onlySearch={} caseFile={} total={} averageHitAtK={} averageRecallAtK={} averageMrr={} averageLatencyMs={} totalTokens={}",
                safeRequest.isOnlySearch(),
                safeRequest.getCaseFile(),
                summary.getTotal(),
                summary.getAverageHitAtK(),
                summary.getAverageRecallAtK(),
                summary.getAverageMrr(),
                summary.getAverageLatencyMs(),
                summary.getTotalTokens());
        return new RagEvalResponse(safeRequest.isOnlySearch(), safeRequest.getCaseFile(), summary, results);
    }

    public List<RagEvalCase> loadCases(String caseFile) {
        if (!StringUtils.hasText(caseFile)) {
            return builtInCases;
        }
        Path path = Path.of(caseFile).normalize();
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("eval case file not found: " + caseFile);
        }
        try {
            return objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
        } catch (IOException exception) {
            throw new IllegalArgumentException("failed to read eval case file: " + caseFile, exception);
        }
    }

    private RagEvalResult evaluateCase(RagEvalCase evalCase, boolean onlySearch) {
        long startNanos = System.nanoTime();
        List<RagSearchResult> retrievedChunks = ragSearchService.search(evalCase.getQuestion());
        RetrievalMetrics retrievalMetrics = calculateRetrievalMetrics(
                normalizeList(evalCase.getExpectedSourceChunkIds()),
                normalizeList(evalCase.getExpectedSourceDocumentIds()),
                retrievedChunks
        );

        RagAskResponse askResponse = null;
        if (!onlySearch) {
            askResponse = ragAskService.ask(evalCase.getQuestion());
        }

        String answer = askResponse == null ? null : askResponse.getAnswer();
        List<RagSource> sources = askResponse == null || askResponse.getSources() == null ? List.of() : askResponse.getSources();
        RagTokenUsage tokenUsage = askResponse == null || askResponse.getTokenUsage() == null
                ? new RagTokenUsage(0, 0, 0)
                : askResponse.getTokenUsage();

        RagEvalResult result = new RagEvalResult();
        result.setCaseId(evalCase.getCaseId());
        result.setQuestion(evalCase.getQuestion());
        result.setRetrievedChunks(retrievedChunks);
        result.setExpectedSourceChunkIds(normalizeList(evalCase.getExpectedSourceChunkIds()));
        result.setExpectedSourceDocumentIds(normalizeList(evalCase.getExpectedSourceDocumentIds()));
        result.setHitAtK(retrievalMetrics.hitAtK());
        result.setRecallAtK(retrievalMetrics.recallAtK());
        result.setMrr(retrievalMetrics.mrr());
        result.setHitChunkIds(retrievalMetrics.hitChunkIds());
        result.setAnswer(answer);
        result.setSources(sources);
        result.setHasAnswer(StringUtils.hasText(answer));
        result.setNoAnswerFallback(NO_ANSWER.equals(answer));
        result.setSourcesContainExpectedDocuments(sourcesContainExpectedDocuments(evalCase.getExpectedSourceDocumentIds(), sources));
        result.setAnswerContainsExpectedPhrase(answerContainsExpectedPhrase(evalCase.getExpectedAnswer(), answer));
        result.setLatencyMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        result.setTokenUsage(tokenUsage);
        return result;
    }

    private RagEvalResult evaluateCaseSafely(RagEvalCase evalCase, boolean onlySearch) {
        long startNanos = System.nanoTime();
        try {
            return evaluateCase(evalCase, onlySearch);
        } catch (RuntimeException exception) {
            RagEvalResult result = new RagEvalResult();
            result.setCaseId(evalCase == null ? null : evalCase.getCaseId());
            result.setQuestion(evalCase == null ? null : evalCase.getQuestion());
            result.setRetrievedChunks(List.of());
            result.setExpectedSourceChunkIds(evalCase == null ? List.of() : normalizeList(evalCase.getExpectedSourceChunkIds()));
            result.setExpectedSourceDocumentIds(evalCase == null ? List.of() : normalizeList(evalCase.getExpectedSourceDocumentIds()));
            result.setHitAtK(false);
            result.setRecallAtK(0.0);
            result.setMrr(0.0);
            result.setHitChunkIds(List.of());
            result.setSources(List.of());
            result.setHasAnswer(false);
            result.setNoAnswerFallback(false);
            result.setSourcesContainExpectedDocuments(false);
            result.setAnswerContainsExpectedPhrase(false);
            result.setLatencyMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            result.setTokenUsage(new RagTokenUsage(0, 0, 0));
            result.setSuccess(false);
            result.setErrorCode(errorCode(exception));
            result.setErrorMessage(limit(exception.getMessage(), 300));
            log.warn("rag_eval_case_failed caseId={} errorCode={} message={}",
                    result.getCaseId(),
                    result.getErrorCode(),
                    result.getErrorMessage());
            return result;
        }
    }

    /**
     * chunk 粒度期望列表非空时按 chunk id 匹配（严格）；否则回退到 document 粒度按 filename
     * 或 documentId 二选一命中，让 seed 灌入的动态 UUID 场景也能配出有意义的 Hit/Recall/MRR。
     * expectedDocumentTokens 里传的可能是"01-年假与请假制度.md"这类文件名，也可能是数据库 UUID。
     */
    static RetrievalMetrics calculateRetrievalMetrics(
            List<String> expectedChunkIds,
            List<String> expectedDocumentTokens,
            List<RagSearchResult> retrievedChunks) {
        List<String> expChunks = normalizeList(expectedChunkIds);
        if (!expChunks.isEmpty()) {
            return calculateByChunkId(expChunks, retrievedChunks);
        }
        List<String> expDocs = normalizeList(expectedDocumentTokens);
        if (!expDocs.isEmpty()) {
            return calculateByDocument(expDocs, retrievedChunks);
        }
        return new RetrievalMetrics(false, 0.0, 0.0, List.of());
    }

    private static RetrievalMetrics calculateByChunkId(List<String> expected, List<RagSearchResult> retrievedChunks) {
        Set<String> expectedSet = new HashSet<>(expected);
        List<String> hitChunkIds = new ArrayList<>();
        int firstHitRank = 0;
        for (int index = 0; index < retrievedChunks.size(); index++) {
            String chunkId = retrievedChunks.get(index).getChunkId();
            if (chunkId != null && expectedSet.contains(chunkId)) {
                hitChunkIds.add(chunkId);
                if (firstHitRank == 0) {
                    firstHitRank = index + 1;
                }
            }
        }
        double recallAtK = (double) new HashSet<>(hitChunkIds).size() / expectedSet.size();
        double mrr = firstHitRank == 0 ? 0.0 : 1.0 / firstHitRank;
        return new RetrievalMetrics(!hitChunkIds.isEmpty(), recallAtK, mrr, hitChunkIds);
    }

    private static RetrievalMetrics calculateByDocument(List<String> expected, List<RagSearchResult> retrievedChunks) {
        Set<String> expectedSet = new HashSet<>(expected);
        Set<String> hitDocs = new HashSet<>();
        List<String> hitChunkIds = new ArrayList<>();
        int firstHitRank = 0;
        for (int index = 0; index < retrievedChunks.size(); index++) {
            RagSearchResult chunk = retrievedChunks.get(index);
            String matchedToken = null;
            if (chunk.getFilename() != null && expectedSet.contains(chunk.getFilename())) {
                matchedToken = chunk.getFilename();
            } else if (chunk.getDocumentId() != null && expectedSet.contains(chunk.getDocumentId())) {
                matchedToken = chunk.getDocumentId();
            }
            if (matchedToken != null) {
                hitDocs.add(matchedToken);
                if (chunk.getChunkId() != null) {
                    hitChunkIds.add(chunk.getChunkId());
                }
                if (firstHitRank == 0) {
                    firstHitRank = index + 1;
                }
            }
        }
        double recallAtK = (double) hitDocs.size() / expectedSet.size();
        double mrr = firstHitRank == 0 ? 0.0 : 1.0 / firstHitRank;
        return new RetrievalMetrics(!hitDocs.isEmpty(), recallAtK, mrr, hitChunkIds);
    }

    private static RagEvalSummary summarize(List<RagEvalResult> results) {
        int total = results.size();
        if (total == 0) {
            return new RagEvalSummary(0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0);
        }
        int promptTokens = results.stream()
                .map(RagEvalResult::getTokenUsage)
                .mapToInt(usage -> usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens())
                .sum();
        int completionTokens = results.stream()
                .map(RagEvalResult::getTokenUsage)
                .mapToInt(usage -> usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens())
                .sum();
        int totalTokens = results.stream()
                .map(RagEvalResult::getTokenUsage)
                .mapToInt(usage -> usage == null || usage.getTotalTokens() == null ? 0 : usage.getTotalTokens())
                .sum();
        return new RagEvalSummary(
                total,
                results.stream().mapToDouble(result -> result.isHitAtK() ? 1.0 : 0.0).average().orElse(0.0),
                results.stream().mapToDouble(RagEvalResult::getRecallAtK).average().orElse(0.0),
                results.stream().mapToDouble(RagEvalResult::getMrr).average().orElse(0.0),
                results.stream().mapToLong(RagEvalResult::getLatencyMs).average().orElse(0.0),
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    private static boolean sourcesContainExpectedDocuments(List<String> expectedDocumentIds, List<RagSource> sources) {
        List<String> expected = normalizeList(expectedDocumentIds);
        if (expected.isEmpty()) {
            return true;
        }
        Set<String> actualIds = sources.stream()
                .map(RagSource::getDocumentId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Set<String> actualFilenames = sources.stream()
                .map(RagSource::getFilename)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return expected.stream().allMatch(exp -> actualIds.contains(exp) || actualFilenames.contains(exp));
    }

    private static boolean answerContainsExpectedPhrase(String expectedAnswer, String answer) {
        if (!StringUtils.hasText(expectedAnswer)) {
            return true;
        }
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        if (answer.contains(expectedAnswer)) {
            return true;
        }
        String normalizedAnswer = answer.toLowerCase(Locale.ROOT);
        return keyPhrases(expectedAnswer).stream()
                .anyMatch(phrase -> normalizedAnswer.contains(phrase.toLowerCase(Locale.ROOT)));
    }

    private static List<String> keyPhrases(String expectedAnswer) {
        if (!StringUtils.hasText(expectedAnswer)) {
            return List.of();
        }
        return List.of(expectedAnswer.split("[,，。；;、\\s]+")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(phrase -> phrase.length() >= 2)
                .toList();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof LlmException llmException) {
            return llmException.getErrorType().name();
        }
        return exception.getClass().getSimpleName();
    }

    private static String limit(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }

    record RetrievalMetrics(boolean hitAtK, double recallAtK, double mrr, List<String> hitChunkIds) {
    }
}
