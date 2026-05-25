package com.yhl.rag.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class SearchKnowledgeToolRequest {

    @NotBlank(message = "query is required")
    @Size(max = 500, message = "query length must be <= 500")
    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
