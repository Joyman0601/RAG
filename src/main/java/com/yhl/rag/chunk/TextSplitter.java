package com.yhl.rag.chunk;

public interface TextSplitter {

    ChunkStrategy strategy();

    ChunkResult split(String documentId, String filename, String text, ChunkConfig config);
}
