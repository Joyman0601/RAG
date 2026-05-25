package com.yhl.rag.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final String ERROR_EMPTY_FILE = "DOCUMENT_EMPTY_FILE";
    private static final String ERROR_UNSUPPORTED_TYPE = "DOCUMENT_UNSUPPORTED_TYPE";
    private static final String ERROR_READ_FAILED = "DOCUMENT_READ_FAILED";
    private static final String ERROR_DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";

    private final RagProperties ragProperties;
    private final LlmProperties llmProperties;
    private final EmbeddingClient embeddingClient;
    private final MockCurrentUserProvider currentUserProvider;
    private final ConcurrentMap<String, DocumentInfo> documentInfoStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> documentTextStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<DocumentChunk>> documentChunkStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Double>> chunkEmbeddingStore = new ConcurrentHashMap<>();

    public DocumentService(
            RagProperties ragProperties,
            LlmProperties llmProperties,
            EmbeddingClient embeddingClient,
            MockCurrentUserProvider currentUserProvider
    ) {
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.embeddingClient = embeddingClient;
        this.currentUserProvider = currentUserProvider;
    }

    public DocumentInfo upload(MultipartFile file) {
        validateFile(file);

        String filename = safeFilename(file.getOriginalFilename());
        String contentType = normalizeContentType(file.getContentType());
        String content = readText(file);
        String id = UUID.randomUUID().toString();
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        DocumentVisibility visibility = DocumentVisibility.INTERNAL;
        DocumentInfo documentInfo = new DocumentInfo(
                id,
                filename,
                contentType,
                file.getSize(),
                Instant.now(),
                DocumentStatus.ACTIVE,
                1,
                currentUser.getUserId(),
                currentUser.getDepartment(),
                visibility,
                currentUser.getPermissionLevel()
        );

        List<DocumentChunk> chunks = chunkText(
                id,
                filename,
                content,
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                1,
                currentUser.getUserId(),
                currentUser.getDepartment(),
                visibility,
                currentUser.getPermissionLevel()
        );
        Map<String, List<Double>> embeddings = embedChunks(id, chunks);

        documentInfoStore.put(id, documentInfo);
        documentTextStore.put(id, content);
        documentChunkStore.put(id, List.copyOf(chunks));
        chunkEmbeddingStore.putAll(embeddings);

        log.info("document_upload id={} filename={} contentType={} size={} textChars={} chunkCount={} ownerId={} department={} visibility={} permissionLevel={}",
                id,
                filename,
                contentType,
                file.getSize(),
                content.length(),
                chunks.size(),
                currentUser.getUserId(),
                currentUser.getDepartment(),
                visibility,
                currentUser.getPermissionLevel());
        return documentInfo;
    }

    public DocumentInfo update(String documentId, MultipartFile file) {
        if (!StringUtils.hasText(documentId)) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "documentId 不能为空");
        }
        validateFile(file);

        DocumentInfo existingDocument = documentInfoStore.get(documentId);
        if (existingDocument == null || existingDocument.getStatus() == DocumentStatus.DELETED) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "文档不存在：" + documentId);
        }

        int oldVersion = existingDocument.getVersion();
        int newVersion = oldVersion + 1;
        List<DocumentChunk> oldChunks = new ArrayList<>(documentChunkStore.getOrDefault(documentId, List.of()));

        String filename = safeFilename(file.getOriginalFilename());
        String contentType = normalizeContentType(file.getContentType());
        String content = readText(file);
        DocumentVisibility visibility = existingDocument.getVisibility();

        List<DocumentChunk> newChunks = chunkText(
                documentId,
                filename,
                content,
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                newVersion,
                existingDocument.getOwnerId(),
                existingDocument.getDepartment(),
                visibility,
                existingDocument.getPermissionLevel()
        );
        Map<String, List<Double>> embeddings = embedChunks(documentId, newChunks);

        int deletedChunkCount = deactivateChunksAndEmbeddings(oldChunks);
        existingDocument.setFilename(filename);
        existingDocument.setContentType(contentType);
        existingDocument.setSize(file.getSize());
        existingDocument.setStatus(DocumentStatus.ACTIVE);
        existingDocument.setVersion(newVersion);

        List<DocumentChunk> allChunks = new ArrayList<>(oldChunks);
        allChunks.addAll(newChunks);
        documentTextStore.put(documentId, content);
        documentChunkStore.put(documentId, List.copyOf(allChunks));
        chunkEmbeddingStore.putAll(embeddings);

        log.info("document_update documentId={} oldVersion={} newVersion={} deletedChunkCount={} newChunkCount={}",
                documentId,
                oldVersion,
                newVersion,
                deletedChunkCount,
                newChunks.size());
        return existingDocument;
    }

    public DocumentInfo delete(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "documentId 不能为空");
        }

        DocumentInfo documentInfo = documentInfoStore.get(documentId);
        if (documentInfo == null) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "文档不存在：" + documentId);
        }

        int oldVersion = documentInfo.getVersion();
        List<DocumentChunk> chunks = new ArrayList<>(documentChunkStore.getOrDefault(documentId, List.of()));
        int deletedChunkCount = deactivateChunksAndEmbeddings(chunks);
        documentInfo.setStatus(DocumentStatus.DELETED);
        documentChunkStore.put(documentId, List.copyOf(chunks));
        documentTextStore.remove(documentId);

        log.info("document_delete documentId={} oldVersion={} newVersion={} deletedChunkCount={} newChunkCount={}",
                documentId,
                oldVersion,
                documentInfo.getVersion(),
                deletedChunkCount,
                0);
        return documentInfo;
    }

    public List<DocumentChunk> chunkText(String documentId, String filename, String text, int chunkSize, int overlap) {
        return chunkText(
                documentId,
                filename,
                text,
                chunkSize,
                overlap,
                1,
                null,
                null,
                DocumentVisibility.INTERNAL,
                0
        );
    }

    public List<DocumentChunk> chunkText(
            String documentId,
            String filename,
            String text,
            int chunkSize,
            int overlap,
            int version,
            String ownerId,
            String department,
            DocumentVisibility visibility,
            int permissionLevel
    ) {
        validateChunkConfig(chunkSize, overlap);

        String normalizedText = text == null ? "" : text.trim();
        int originalTextLength = text == null ? 0 : text.length();
        if (normalizedText.isEmpty()) {
            log.info("document_chunk documentId={} textChars={} chunkSize={} overlap={} chunkCount={}",
                    documentId,
                    originalTextLength,
                    chunkSize,
                    overlap,
                    0);
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;
        Instant createdAt = Instant.now();

        while (start < normalizedText.length()) {
            int end = Math.min(start + chunkSize, normalizedText.length());
            String chunkContent = normalizedText.substring(start, end);
            chunks.add(new DocumentChunk(
                    UUID.randomUUID().toString(),
                    documentId,
                    filename,
                    chunkContent,
                    sha256Hex(chunkContent),
                    chunkIndex,
                    createdAt,
                    DocumentStatus.ACTIVE,
                    version,
                    ownerId,
                    department,
                    visibility,
                    permissionLevel
            ));

            if (end == normalizedText.length()) {
                break;
            }

            start = end - overlap;
            chunkIndex++;
        }

        log.info("document_chunk documentId={} textChars={} chunkSize={} overlap={} chunkCount={}",
                documentId,
                originalTextLength,
                chunkSize,
                overlap,
                chunks.size());
        return chunks;
    }

    public List<DocumentInfo> listDocuments() {
        return documentInfoStore.values().stream()
                .sorted(Comparator.comparing(DocumentInfo::getCreatedAt).reversed())
                .toList();
    }

    public String getDocumentText(String id) {
        return documentTextStore.get(id);
    }

    public List<DocumentChunk> listChunks(String documentId) {
        if (!StringUtils.hasText(documentId)) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "documentId 不能为空");
        }

        List<DocumentChunk> chunks = documentChunkStore.get(documentId);
        if (chunks == null) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "文档不存在：" + documentId);
        }

        return chunks.stream()
                .sorted(Comparator.comparingInt(DocumentChunk::getVersion)
                        .thenComparingInt(DocumentChunk::getChunkIndex))
                .toList();
    }

    public List<Double> getChunkEmbedding(String chunkId) {
        return chunkEmbeddingStore.get(chunkId);
    }

    public List<DocumentChunk> listAllChunks() {
        return documentChunkStore.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    public Map<String, List<Double>> getChunkEmbeddingsSnapshot() {
        return Map.copyOf(chunkEmbeddingStore);
    }

    public Map<String, DocumentInfo> getDocumentInfoSnapshot() {
        return Map.copyOf(documentInfoStore);
    }

    private Map<String, List<Double>> embedChunks(String documentId, List<DocumentChunk> chunks) {
        Map<String, List<Double>> embeddings = new HashMap<>();
        for (DocumentChunk chunk : chunks) {
            long startNanos = System.nanoTime();
            List<Double> vector = embeddingClient.embed(chunk.getContent());
            embeddings.put(chunk.getChunkId(), List.copyOf(vector));
            log.info("document_chunk_embedding documentId={} chunkId={} model={} vectorDimension={} durationMs={}",
                    documentId,
                    chunk.getChunkId(),
                    llmProperties.getEmbeddingModel(),
                    vector.size(),
                    elapsedMillis(startNanos));
        }
        return embeddings;
    }

    private int deactivateChunksAndEmbeddings(List<DocumentChunk> chunks) {
        int deletedChunkCount = 0;
        for (DocumentChunk chunk : chunks) {
            if (chunk.getStatus() == DocumentStatus.ACTIVE) {
                deletedChunkCount++;
            }
            chunk.setStatus(DocumentStatus.DELETED);
            chunkEmbeddingStore.remove(chunk.getChunkId());
        }
        return deletedChunkCount;
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentException(ERROR_EMPTY_FILE, "上传文件不能为空");
        }

        String originalFilename = file.getOriginalFilename();
        String filename = safeFilename(originalFilename);
        String contentType = normalizeContentType(file.getContentType());

        if (StringUtils.hasText(originalFilename) && !isSupportedFilename(filename)) {
            throw new DocumentException(
                    ERROR_UNSUPPORTED_TYPE,
                    "仅支持上传 txt、md、markdown 文件"
            );
        }

        if (!StringUtils.hasText(originalFilename) && !isSupportedContentType(contentType)) {
            throw new DocumentException(
                    ERROR_UNSUPPORTED_TYPE,
                    "仅支持上传 txt、md、markdown 文件"
            );
        }
    }

    private static String readText(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new DocumentException(ERROR_READ_FAILED, "读取上传文件失败", exception);
        }
    }

    private static void validateChunkConfig(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new DocumentException("DOCUMENT_INVALID_CHUNK_CONFIG", "chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new DocumentException("DOCUMENT_INVALID_CHUNK_CONFIG", "overlap 不能小于 0");
        }
        if (overlap >= chunkSize) {
            throw new DocumentException("DOCUMENT_INVALID_CHUNK_CONFIG", "overlap 必须小于 chunkSize");
        }
    }

    private static boolean isSupportedFilename(String filename) {
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        return lowerFilename.endsWith(".txt")
                || lowerFilename.endsWith(".md")
                || lowerFilename.endsWith(".markdown");
    }

    private static boolean isSupportedContentType(String contentType) {
        return contentType.startsWith("text/plain")
                || contentType.startsWith("text/markdown")
                || contentType.startsWith("text/x-markdown")
                || "application/octet-stream".equals(contentType);
    }

    private static String safeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "unknown";
        }
        String normalizedFilename = filename.replace("\\", "/");
        return normalizedFilename.substring(normalizedFilename.lastIndexOf('/') + 1);
    }

    private static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        return contentType.toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new DocumentException("DOCUMENT_HASH_FAILED", "计算 chunk hash 失败", exception);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
