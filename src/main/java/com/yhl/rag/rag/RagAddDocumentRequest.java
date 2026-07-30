package com.yhl.rag.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RagAddDocumentRequest {

    @NotBlank(message = "title cannot be blank")
    @Size(max = 200, message = "title cannot exceed 200 characters")
    private String title;

    @NotBlank(message = "content cannot be blank")
    @Size(max = 10000, message = "content cannot exceed 10000 characters")
    private String content;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
