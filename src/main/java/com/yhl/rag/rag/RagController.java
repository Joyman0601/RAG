package com.yhl.rag.rag;

import java.util.List;

import com.yhl.rag.security.MockCurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;
    private final RagSearchService ragSearchService;
    private final RagAskService ragAskService;
    private final RagEvalService ragEvalService;
    private final MockCurrentUserProvider currentUserProvider;

    public RagController(
            RagService ragService,
            RagSearchService ragSearchService,
            RagAskService ragAskService,
            RagEvalService ragEvalService,
            MockCurrentUserProvider currentUserProvider
    ) {
        this.ragService = ragService;
        this.ragSearchService = ragSearchService;
        this.ragAskService = ragAskService;
        this.ragEvalService = ragEvalService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/documents")
    public RagDocument addDocument(@Valid @RequestBody RagAddDocumentRequest request) {
        return ragService.addDocument(request.getTitle(), request.getContent());
    }

    @GetMapping("/documents")
    public List<RagDocument> listDocuments() {
        return ragService.listDocuments();
    }

    @PostMapping("/query")
    public RagQueryResponse query(@Valid @RequestBody RagQueryRequest request) {
        return ragService.query(request.getQuestion());
    }

    @PostMapping("/search")
    public List<RagSearchResult> search(
            @Valid @RequestBody RagSearchRequest request,
            @RequestParam(name = "includeBelowThreshold", defaultValue = "false") boolean includeBelowThreshold
    ) {
        return ragSearchService.searchWithMetrics(
                request.getQuestion(),
                currentUserProvider.getCurrentUser(),
                includeBelowThreshold,
                null,
                request.getMode()
        ).results();
    }

    @PostMapping("/ask")
    public RagAskResponse ask(
            @Valid @RequestBody RagAskRequest request,
            @RequestParam(name = "debug", defaultValue = "false") boolean debug
    ) {
        return ragAskService.ask(request.getQuestion(), request.getConversationId(), debug, request.getMode());
    }

    @GetMapping("/eval")
    public RagEvalResponse eval(@RequestParam(name = "onlySearch", defaultValue = "false") boolean onlySearch) {
        return ragEvalService.evaluate(onlySearch);
    }

    @PostMapping("/eval/run")
    public RagEvalResponse runEval(@RequestBody(required = false) RagEvalRunRequest request) {
        return ragEvalService.evaluate(request);
    }
}
