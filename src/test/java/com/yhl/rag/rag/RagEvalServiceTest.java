package com.yhl.rag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.llm.LlmErrorType;
import com.yhl.rag.llm.LlmException;
import java.util.List;

import org.junit.jupiter.api.Test;

class RagEvalServiceTest {

    private static RagSearchResult chunk(String chunkId, String filename, String documentId) {
        RagSearchResult r = new RagSearchResult();
        r.setChunkId(chunkId);
        r.setFilename(filename);
        r.setDocumentId(documentId);
        return r;
    }

    @Test
    void calculateRetrievalMetrics_whenExpectedChunkIsHit_returnsHitAtK() {
        RagEvalService.RetrievalMetrics metrics = RagEvalService.calculateRetrievalMetrics(
                List.of("chunk-2"),
                List.of(),
                List.of(chunk("chunk-1", null, null), chunk("chunk-2", null, null), chunk("chunk-3", null, null))
        );

        assertThat(metrics.hitAtK()).isTrue();
        assertThat(metrics.hitChunkIds()).containsExactly("chunk-2");
    }

    @Test
    void calculateRetrievalMetrics_whenMultipleExpectedChunks_returnsCorrectRecallAtK() {
        RagEvalService.RetrievalMetrics metrics = RagEvalService.calculateRetrievalMetrics(
                List.of("chunk-1", "chunk-2", "chunk-3"),
                List.of(),
                List.of(chunk("chunk-2", null, null), chunk("chunk-9", null, null), chunk("chunk-3", null, null))
        );

        assertThat(metrics.recallAtK()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void calculateRetrievalMetrics_whenFirstHitRankChanges_returnsCorrectMrr() {
        RagEvalService.RetrievalMetrics metrics = RagEvalService.calculateRetrievalMetrics(
                List.of("chunk-3"),
                List.of(),
                List.of(chunk("chunk-1", null, null), chunk("chunk-2", null, null), chunk("chunk-3", null, null))
        );

        assertThat(metrics.mrr()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void calculateRetrievalMetrics_whenNoExpectedChunkIsHit_returnsZeroMetrics() {
        RagEvalService.RetrievalMetrics metrics = RagEvalService.calculateRetrievalMetrics(
                List.of("chunk-expected"),
                List.of(),
                List.of(chunk("chunk-1", null, null), chunk("chunk-2", null, null), chunk("chunk-3", null, null))
        );

        assertThat(metrics.hitAtK()).isFalse();
        assertThat(metrics.recallAtK()).isZero();
        assertThat(metrics.mrr()).isZero();
        assertThat(metrics.hitChunkIds()).isEmpty();
    }

    @Test
    void calculateRetrievalMetrics_whenExpectedDocumentMatchesByFilename_returnsHit() {
        RagEvalService.RetrievalMetrics metrics = RagEvalService.calculateRetrievalMetrics(
                List.of(),
                List.of("01-年假与请假制度.md"),
                List.of(
                        chunk("c1", "05-远程办公政策.md", "doc-5"),
                        chunk("c2", "01-年假与请假制度.md", "doc-1"),
                        chunk("c3", "01-年假与请假制度.md", "doc-1")
                )
        );

        assertThat(metrics.hitAtK()).isTrue();
        assertThat(metrics.mrr()).isEqualTo(1.0 / 2.0);
        assertThat(metrics.recallAtK()).isEqualTo(1.0);
        assertThat(metrics.hitChunkIds()).containsExactly("c2", "c3");
    }

    @Test
    void calculateRetrievalMetrics_whenBothEmpty_returnsZeroMetrics() {
        RagEvalService.RetrievalMetrics metrics = RagEvalService.calculateRetrievalMetrics(
                List.of(),
                List.of(),
                List.of(chunk("c1", "any.md", "doc"))
        );

        assertThat(metrics.hitAtK()).isFalse();
        assertThat(metrics.recallAtK()).isZero();
        assertThat(metrics.mrr()).isZero();
    }

    @Test
    void evaluate_whenCaseExecutionFails_returnsCaseErrorInsteadOfThrowing() {
        RagSearchService searchService = mock(RagSearchService.class);
        RagAskService askService = mock(RagAskService.class);
        when(searchService.search(anyString())).thenThrow(new LlmException(LlmErrorType.API_KEY_MISSING, "missing key"));
        RagEvalService evalService = new RagEvalService(searchService, askService, new ObjectMapper());

        RagEvalResponse response = evalService.evaluate(new RagEvalRunRequest());

        assertThat(response.getResults()).isNotEmpty();
        assertThat(response.getResults().get(0).isSuccess()).isFalse();
        assertThat(response.getResults().get(0).getErrorCode()).isEqualTo("API_KEY_MISSING");
    }
}
