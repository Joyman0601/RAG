package com.yhl.rag.rag;

public class RagSearchResult {

    private static final int PREVIEW_LENGTH = 100;

    private String chunkId;

    private String documentId;

    private String filename;

    private int chunkIndex;

    private String content;

    private String contentPreview;

    private double score;

    private String debugInfo;

    private boolean included;

    // 命中子块所属父块（可空）；parent-document 开启时 buildContext 据此回填父块正文。
    private String parentId;

    // 多模态：命中 chunk 的模态（TEXT/IMAGE）与图片引用，供前端展示召回到的图片。
    private String modality;

    private String imageRef;

    public RagSearchResult() {
    }

    public RagSearchResult(String chunkId, String documentId, String filename, int chunkIndex, String content, double score) {
        this(chunkId, documentId, filename, chunkIndex, content, score, null, true);
    }

    public RagSearchResult(
            String chunkId,
            String documentId,
            String filename,
            int chunkIndex,
            String content,
            double score,
            String debugInfo,
            boolean included
    ) {
        this.chunkId = chunkId;
        this.documentId = documentId;
        this.filename = filename;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.contentPreview = preview(content);
        this.score = score;
        this.debugInfo = debugInfo;
        this.included = included;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
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

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
        this.contentPreview = preview(content);
    }

    public String getContentPreview() {
        return contentPreview;
    }

    public void setContentPreview(String contentPreview) {
        this.contentPreview = contentPreview;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getDebugInfo() {
        return debugInfo;
    }

    public void setDebugInfo(String debugInfo) {
        this.debugInfo = debugInfo;
    }

    public boolean isIncluded() {
        return included;
    }

    public void setIncluded(boolean included) {
        this.included = included;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    public String getImageRef() {
        return imageRef;
    }

    public void setImageRef(String imageRef) {
        this.imageRef = imageRef;
    }

    private static String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= PREVIEW_LENGTH ? content : content.substring(0, PREVIEW_LENGTH);
    }
}
