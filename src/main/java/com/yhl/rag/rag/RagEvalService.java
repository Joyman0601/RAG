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
                    "leave-sick-materials",
                    "病假需要提交什么材料？",
                    "病假 材料",
                    List.of(),
                    List.of(),
                    null,
                    "user_001",
                    List.of("leave")
            ),
            new RagEvalCase(
                    "leave-annual-days",
                    "年假有多少天？",
                    "年假 天",
                    List.of(),
                    List.of(),
                    null,
                    "user_001",
                    List.of("leave")
            ),
            new RagEvalCase(
                    "reimbursement-invoice",
                    "报销需要发票吗？",
                    "报销 发票",
                    List.of(),
                    List.of(),
                    null,
                    "user_001",
                    List.of("reimbursement")
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
                retrievedChunks.stream().map(RagSearchResult::getChunkId).toList()
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

    static RetrievalMetrics calculateRetrievalMetrics(List<String> expectedChunkIds, List<String> retrievedChunkIds) {
        List<String> expected = normalizeList(expectedChunkIds);
        List<String> retrieved = normalizeList(retrievedChunkIds);
        if (expected.isEmpty()) {
            return new RetrievalMetrics(false, 0.0, 0.0, List.of());
        }

        Set<String> expectedSet = new HashSet<>(expected);
        List<String> hitChunkIds = new ArrayList<>();
        int firstHitRank = 0;
        for (int index = 0; index < retrieved.size(); index++) {
            String chunkId = retrieved.get(index);
            if (expectedSet.contains(chunkId)) {
                hitChunkIds.add(chunkId);
                if (firstHitRank == 0) {
                    firstHitRank = index + 1;
                }
            }
        }
        double recallAtK = expected.isEmpty() ? 0.0 : (double) new HashSet<>(hitChunkIds).size() / expectedSet.size();
        double mrr = firstHitRank == 0 ? 0.0 : 1.0 / firstHitRank;
        return new RetrievalMetrics(!hitChunkIds.isEmpty(), recallAtK, mrr, hitChunkIds);
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
        Set<String> actual = sources.stream()
                .map(RagSource::getDocumentId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return actual.containsAll(expected);
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
