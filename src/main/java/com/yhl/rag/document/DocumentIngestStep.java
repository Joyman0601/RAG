package com.yhl.rag.document;

public enum DocumentIngestStep {
    SAVE_FILE,
    PARSE,
    CHUNK,
    EMBEDDING,
    INDEXING,
    DONE
}
