package com.yhl.rag.document;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentInfo upload(@NotNull(message = "file cannot be null") @RequestPart("file") MultipartFile file) {
        return documentService.upload(file);
    }

    @GetMapping
    public List<DocumentInfo> listDocuments() {
        return documentService.listDocuments();
    }

    @GetMapping("/{documentId}/chunks")
    public List<DocumentChunk> listChunks(@NotBlank(message = "documentId cannot be blank") @PathVariable String documentId) {
        return documentService.listChunks(documentId);
    }
}
