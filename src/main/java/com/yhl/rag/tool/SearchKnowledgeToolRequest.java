package com.yhl.rag.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class SearchKnowledgeToolRequest {

    @NotBlank(message = "query is required")
    @Size(max = 500, message = "query length must be <= 500")
    private String query;

    @Min(value = 1, message = "topK must be >= 1")
    @Max(value = 10, message = "topK must be <= 10")
    private Integer topK;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
