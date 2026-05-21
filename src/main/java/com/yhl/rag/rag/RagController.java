package com.yhl.rag.rag;

import java.util.List;

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

    public RagController(RagService ragService, RagSearchService ragSearchService, RagAskService ragAskService) {
        this.ragService = ragService;
        this.ragSearchService = ragSearchService;
        this.ragAskService = ragAskService;
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
        return ragSearchService.search(request.getQuestion(), includeBelowThreshold);
    }

    @PostMapping("/ask")
    public RagAskResponse ask(
            @Valid @RequestBody RagAskRequest request,
            @RequestParam(name = "debug", defaultValue = "false") boolean debug
    ) {
        return ragAskService.ask(request.getQuestion(), debug);
    }
}
