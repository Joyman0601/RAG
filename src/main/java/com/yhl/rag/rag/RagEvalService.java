package com.yhl.rag.rag;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RagEvalService {

    private static final Logger log = LoggerFactory.getLogger(RagEvalService.class);

    private final RagSearchService ragSearchService;
    private final RagAskService ragAskService;
    private final List<RagEvalCase> cases = List.of(
            new RagEvalCase(
                    "leave-sick-materials",
                    "病假需要提交什么材料？",
                    "应说明病假所需证明或审批材料。",
                    "",
                    List.of("病假", "材料")
            ),
            new RagEvalCase(
                    "leave-annual-days",
                    "年假有多少天？",
                    "应说明年假天数或计算规则。",
                    "",
                    List.of("年假")
            ),
            new RagEvalCase(
                    "reimbursement-invoice",
                    "报销需要发票吗？",
                    "应说明报销是否需要发票及相关要求。",
                    "",
                    List.of("报销", "发票")
            ),
            new RagEvalCase(
                    "remote-work-approval",
                    "远程办公需要审批吗？",
                    "应说明远程办公审批或申请流程。",
                    "",
                    List.of("远程", "审批")
            ),
            new RagEvalCase(
                    "overtime-compensation",
                    "加班可以调休吗？",
                    "应说明加班调休或补偿规则。",
                    "",
                    List.of("加班", "调休")
            ),
            new RagEvalCase(
                    "onboarding-documents",
                    "入职需要准备哪些材料？",
                    "应说明入职材料清单。",
                    "",
                    List.of("入职", "材料")
            )
    );

    public RagEvalService(RagSearchService ragSearchService, RagAskService ragAskService) {
        this.ragSearchService = ragSearchService;
        this.ragAskService = ragAskService;
    }

    public RagEvalResponse evaluate(boolean onlySearch) {
        long startNanos = System.nanoTime();
        List<RagEvalResult> results = cases.stream()
                .map(evalCase -> evaluateCase(evalCase, onlySearch))
                .toList();
        int total = results.size();
        int passed = (int) results.stream()
                .filter(RagEvalResult::isPassed)
                .count();
        int failed = total - passed;
        double passRate = total == 0 ? 0.0 : (double) passed / total;

        log.info("rag_eval onlySearch={} total={} passed={} failed={} passRate={} durationMs={}",
                onlySearch,
                total,
                passed,
                failed,
                passRate,
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        return new RagEvalResponse(total, passed, failed, passRate, onlySearch, results);
    }

    private RagEvalResult evaluateCase(RagEvalCase evalCase, boolean onlySearch) {
        List<RagSearchResult> searchResults = ragSearchService.search(evalCase.getQuestion());
        List<String> retrievedDocumentIds = searchResults.stream()
                .map(RagSearchResult::getDocumentId)
                .distinct()
                .toList();
        boolean hitExpectedDocument = hitExpectedDocument(evalCase.getExpectedDocumentId(), retrievedDocumentIds);

        String actualAnswer = null;
        List<RagSource> sources = List.of();
        String keywordSourceText;
        if (onlySearch) {
            keywordSourceText = searchResults.stream()
                    .map(RagSearchResult::getContent)
                    .collect(Collectors.joining("\n"));
        } else {
            RagAskResponse askResponse = ragAskService.ask(evalCase.getQuestion());
            actualAnswer = askResponse.getAnswer();
            sources = askResponse.getSources() == null ? List.of() : askResponse.getSources();
            keywordSourceText = actualAnswer;
        }

        boolean keywordMatched = keywordMatched(evalCase.getExpectedKeywords(), keywordSourceText);
        boolean passed = hitExpectedDocument && keywordMatched;
        return new RagEvalResult(
                evalCase.getId(),
                evalCase.getQuestion(),
                evalCase.getExpectedAnswer(),
                actualAnswer,
                evalCase.getExpectedDocumentId(),
                retrievedDocumentIds,
                hitExpectedDocument,
                evalCase.getExpectedKeywords(),
                keywordMatched,
                sources,
                passed
        );
    }

    private static boolean hitExpectedDocument(String expectedDocumentId, List<String> retrievedDocumentIds) {
        if (!StringUtils.hasText(expectedDocumentId)) {
            return true;
        }
        return retrievedDocumentIds.contains(expectedDocumentId);
    }

    private static boolean keywordMatched(List<String> expectedKeywords, String text) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return true;
        }
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return expectedKeywords.stream()
                .allMatch(text::contains);
    }
}
