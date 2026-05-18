package com.yhl.rag.rag;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
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
}
