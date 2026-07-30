package com.yhl.rag.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.yhl.rag.document.DocumentException;

/** chunkId 与 contentHash 的稳定生成，保证同文同版同序的 chunk 复算出相同 id（增量索引去重依赖此）。 */
final class ChunkIds {

    private ChunkIds() {
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new DocumentException("DOCUMENT_HASH_FAILED", "计算 chunk hash 失败", exception);
        }
    }

    static String stableChunkId(String documentId, int version, int chunkIndex, String contentHash) {
        String hashPrefix = contentHash == null || contentHash.length() < 16 ? contentHash : contentHash.substring(0, 16);
        return documentId + "-v" + version + "-c" + chunkIndex + "-" + hashPrefix;
    }

    static String stableParentId(String documentId, int version, int sectionIndex) {
        return documentId + "-v" + version + "-p" + sectionIndex;
    }
}
