package com.yhl.rag.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RagSource {

    private String id;

    private String title;

    private double score;

    private String snippet;

    private Integer index;

    private String documentId;

    private String filename;

    private String documentName;

    private String chunkId;

    private Integer chunkIndex;

    public RagSource() {
    }

    public RagSource(String id, String title, int score, String snippet) {
        this.id = id;
        this.title = title;
        this.score = score;
        this.snippet = snippet;
    }

    public RagSource(Integer index, String documentId, String filename, String chunkId, Integer chunkIndex, double score) {
        this.index = index;
        this.documentId = documentId;
        this.filename = filename;
        this.documentName = filename;
        this.chunkId = chunkId;
        this.chunkIndex = chunkIndex;
        this.score = score;
    }

    public RagSource(String documentId, String documentName, String chunkId, double score, String snippet) {
        this.documentId = documentId;
        this.documentName = documentName;
        this.chunkId = chunkId;
        this.score = score;
        this.snippet = snippet;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getDocumentName() {
        return documentName;
    }

    public void setDocumentName(String documentName) {
        this.documentName = documentName;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }
}
