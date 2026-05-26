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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.yhl.rag.llm.EmbeddingClient;
import com.yhl.rag.llm.LlmProperties;
import com.yhl.rag.rag.KnowledgeBaseVersionService;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.security.CurrentUser;
import com.yhl.rag.security.MockCurrentUserProvider;
import com.yhl.rag.vector.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final VectorStore vectorStore;
    private final DocumentIngestTaskService ingestTaskService;
    private final KnowledgeBaseVersionService knowledgeBaseVersionService;
    private final ConcurrentMap<String, DocumentInfo> documentInfoStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> documentTextStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, byte[]> rawDocumentStore = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<DocumentChunk>> documentChunkStore = new ConcurrentHashMap<>();

    @Autowired
    public DocumentService(
            RagProperties ragProperties,
            LlmProperties llmProperties,
            EmbeddingClient embeddingClient,
            MockCurrentUserProvider currentUserProvider,
            VectorStore vectorStore,
            DocumentIngestTaskService ingestTaskService,
            KnowledgeBaseVersionService knowledgeBaseVersionService
    ) {
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.embeddingClient = embeddingClient;
        this.currentUserProvider = currentUserProvider;
        this.vectorStore = vectorStore;
        this.ingestTaskService = ingestTaskService;
        this.knowledgeBaseVersionService = knowledgeBaseVersionService;
    }

    public DocumentService(
            RagProperties ragProperties,
            LlmProperties llmProperties,
            EmbeddingClient embeddingClient,
            MockCurrentUserProvider currentUserProvider,
            VectorStore vectorStore,
            DocumentIngestTaskService ingestTaskService
    ) {
        this(
                ragProperties,
                llmProperties,
                embeddingClient,
                currentUserProvider,
                vectorStore,
                ingestTaskService,
                new KnowledgeBaseVersionService()
        );
    }

    public DocumentUploadResponse upload(MultipartFile file) {
        validateFile(file);

        String filename = safeFilename(file.getOriginalFilename());
        String contentType = normalizeContentType(file.getContentType());
        byte[] rawContent = readBytes(file);
        String id = UUID.randomUUID().toString();
        CurrentUser currentUser = currentUserProvider.getCurrentUser();
        DocumentVisibility visibility = DocumentVisibility.DEPARTMENT;
        DocumentInfo documentInfo = new DocumentInfo(
                id,
                filename,
                contentType,
                file.getSize(),
                Instant.now(),
                currentUser.getTenantId(),
                currentUser.getUserId(),
                currentUser.getDepartment(),
                visibility,
                Set.of(),
                Set.of(),
                DocumentStatus.UPLOADED,
                1,
                currentUser.getPermissionLevel()
        );

        documentInfoStore.put(id, documentInfo);
        rawDocumentStore.put(id, rawContent);
        DocumentIngestTask task = ingestTaskService.create(id, documentInfo.getVersion());
        long knowledgeBaseVersion = knowledgeBaseVersionService.incrementAndGet();

        log.info("document_upload_accepted id={} taskId={} filename={} contentType={} size={} ownerId={} department={} visibility={} permissionLevel={} knowledgeBaseVersion={}",
                id,
                task.getTaskId(),
                filename,
                contentType,
                file.getSize(),
                currentUser.getUserId(),
                currentUser.getDepartment(),
                visibility,
                currentUser.getPermissionLevel(),
                knowledgeBaseVersion);
        return new DocumentUploadResponse(id, task.getTaskId());
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
                existingDocument.getTenantId(),
                existingDocument.getOwnerId(),
                existingDocument.getDepartmentId(),
                visibility,
                existingDocument.getAllowedUserIds(),
                existingDocument.getAllowedRoleIds(),
                existingDocument.getPermissionLevel()
        );
        Map<DocumentChunk, List<Double>> embeddings = embedChunks(documentId, newChunks);

        int deletedChunkCount = deactivateChunksAndEmbeddings(oldChunks);
        existingDocument.setFilename(filename);
        existingDocument.setContentType(contentType);
        existingDocument.setSize(file.getSize());
        existingDocument.setStatus(DocumentStatus.READY);
        existingDocument.setVersion(newVersion);

        List<DocumentChunk> allChunks = new ArrayList<>(oldChunks);
        allChunks.addAll(newChunks);
        documentTextStore.put(documentId, content);
        documentChunkStore.put(documentId, List.copyOf(allChunks));
        vectorStore.saveAll(embeddings);
        long knowledgeBaseVersion = knowledgeBaseVersionService.incrementAndGet();

        log.info("document_update documentId={} oldVersion={} newVersion={} deletedChunkCount={} newChunkCount={} knowledgeBaseVersion={}",
                documentId,
                oldVersion,
                newVersion,
                deletedChunkCount,
                newChunks.size(),
                knowledgeBaseVersion);
        return existingDocument;
    }

    public boolean processIngestTask(DocumentIngestTask task) {
        DocumentInfo documentInfo = documentInfoStore.get(task.getDocumentId());
        if (documentInfo == null || documentInfo.getStatus() == DocumentStatus.DELETED) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "文档不存在：" + task.getDocumentId());
        }
        if (documentInfo.getVersion() != task.getDocumentVersion()) {
            log.info("document_ingest_stale taskId={} documentId={} taskVersion={} currentVersion={}",
                    task.getTaskId(),
                    task.getDocumentId(),
                    task.getDocumentVersion(),
                    documentInfo.getVersion());
            return false;
        }

        documentInfo.setStatus(DocumentStatus.PROCESSING);
        cleanupVersionArtifacts(documentInfo.getId(), task.getDocumentVersion());

        ingestTaskService.updateCurrentStep(task.getTaskId(), DocumentIngestStep.PARSE);
        byte[] rawContent = rawDocumentStore.get(task.getDocumentId());
        if (rawContent == null || rawContent.length == 0) {
            throw new DocumentException(ERROR_EMPTY_FILE, "文档原始内容不存在");
        }
        String content = new String(rawContent, StandardCharsets.UTF_8);

        ingestTaskService.updateCurrentStep(task.getTaskId(), DocumentIngestStep.CHUNK);
        List<DocumentChunk> chunks = chunkText(
                documentInfo.getId(),
                documentInfo.getFilename(),
                content,
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                documentInfo.getVersion(),
                documentInfo.getTenantId(),
                documentInfo.getOwnerId(),
                documentInfo.getDepartmentId(),
                documentInfo.getVisibility(),
                documentInfo.getAllowedUserIds(),
                documentInfo.getAllowedRoleIds(),
                documentInfo.getPermissionLevel()
        );
        for (DocumentChunk chunk : chunks) {
            chunk.setDocumentStatus(DocumentStatus.READY);
        }
        if (chunks.isEmpty()) {
            throw new DocumentException(ERROR_EMPTY_FILE, "文档内容为空，无法生成 chunk");
        }

        ingestTaskService.updateCurrentStep(task.getTaskId(), DocumentIngestStep.EMBEDDING);
        Map<DocumentChunk, List<Double>> embeddings = embedChunks(documentInfo.getId(), chunks);
        if (embeddings.size() != chunks.size()) {
            throw new DocumentException("DOCUMENT_EMBEDDING_COUNT_MISMATCH", "chunk 数和向量数不一致");
        }

        ingestTaskService.updateCurrentStep(task.getTaskId(), DocumentIngestStep.INDEXING);
        vectorStore.saveAll(embeddings);
        for (DocumentChunk chunk : chunks) {
            if (vectorStore.getEmbedding(chunk.getChunkId()) == null) {
                throw new DocumentException("DOCUMENT_VECTOR_SAVE_FAILED", "向量保存数量校验失败");
            }
        }

        DocumentInfo latestDocument = documentInfoStore.get(task.getDocumentId());
        if (latestDocument == null || latestDocument.getStatus() == DocumentStatus.DELETED) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "文档不存在：" + task.getDocumentId());
        }
        if (latestDocument.getVersion() != task.getDocumentVersion()) {
            log.info("document_ingest_stale_before_ready taskId={} documentId={} taskVersion={} currentVersion={}",
                    task.getTaskId(),
                    task.getDocumentId(),
                    task.getDocumentVersion(),
                    latestDocument.getVersion());
            cleanupVersionArtifacts(task.getDocumentId(), task.getDocumentVersion());
            return false;
        }

        documentTextStore.put(documentInfo.getId(), content);
        documentChunkStore.put(documentInfo.getId(), List.copyOf(chunks));
        documentInfo.setStatus(DocumentStatus.READY);
        long knowledgeBaseVersion = knowledgeBaseVersionService.incrementAndGet();

        log.info("document_ingest_processed documentId={} taskId={} textChars={} chunkCount={} knowledgeBaseVersion={}",
                documentInfo.getId(),
                task.getTaskId(),
                content.length(),
                chunks.size(),
                knowledgeBaseVersion);
        return true;
    }

    public void markDocumentFailed(String documentId) {
        DocumentInfo documentInfo = documentInfoStore.get(documentId);
        if (documentInfo != null && documentInfo.getStatus() != DocumentStatus.DELETED) {
            documentInfo.setStatus(DocumentStatus.FAILED);
        }
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
        vectorStore.deleteByDocumentId(documentId);
        documentInfo.setStatus(DocumentStatus.DELETED);
        documentChunkStore.put(documentId, List.copyOf(chunks));
        documentTextStore.remove(documentId);
        rawDocumentStore.remove(documentId);
        long knowledgeBaseVersion = knowledgeBaseVersionService.incrementAndGet();

        log.info("document_delete documentId={} oldVersion={} newVersion={} deletedChunkCount={} newChunkCount={} knowledgeBaseVersion={}",
                documentId,
                oldVersion,
                documentInfo.getVersion(),
                deletedChunkCount,
                0,
                knowledgeBaseVersion);
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
                DocumentVisibility.DEPARTMENT,
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
        return chunkText(
                documentId,
                filename,
                text,
                chunkSize,
                overlap,
                version,
                "tenant-default",
                ownerId,
                department,
                visibility,
                Set.of(),
                Set.of(),
                permissionLevel
        );
    }

    public List<DocumentChunk> chunkText(
            String documentId,
            String filename,
            String text,
            int chunkSize,
            int overlap,
            int version,
            String tenantId,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds,
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
                    stableChunkId(documentId, version, chunkIndex, sha256Hex(chunkContent)),
                    documentId,
                    filename,
                    chunkContent,
                    sha256Hex(chunkContent),
                    chunkIndex,
                    createdAt,
                    tenantId,
                    ownerId,
                    departmentId,
                    visibility,
                    allowedUserIds,
                    allowedRoleIds,
                    DocumentStatus.ACTIVE,
                    version,
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
        return vectorStore.getEmbedding(chunkId);
    }

    public List<DocumentChunk> listAllChunks() {
        return documentChunkStore.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    public Map<String, List<Double>> getChunkEmbeddingsSnapshot() {
        return vectorStore.getEmbeddingSnapshot();
    }

    public Map<String, DocumentInfo> getDocumentInfoSnapshot() {
        return Map.copyOf(documentInfoStore);
    }

    public Map<String, Integer> getReadyDocumentVersionSnapshot() {
        Map<String, Integer> versions = new HashMap<>();
        for (DocumentInfo documentInfo : documentInfoStore.values()) {
            if (documentInfo.getStatus() == DocumentStatus.READY) {
                versions.put(documentInfo.getId(), documentInfo.getCurrentVersion());
            }
        }
        return Map.copyOf(versions);
    }

    public boolean canAccessChunk(DocumentChunk chunk, CurrentUser currentUser) {
        if (chunk == null || currentUser == null) {
            return false;
        }
        if (chunk.getStatus() != DocumentStatus.ACTIVE || chunk.getDocumentStatus() != DocumentStatus.READY) {
            return false;
        }
        DocumentInfo documentInfo = documentInfoStore.get(chunk.getDocumentId());
        if (documentInfo == null || documentInfo.getStatus() != DocumentStatus.READY) {
            return false;
        }
        if (!equalsText(documentInfo.getTenantId(), currentUser.getTenantId())
                || !equalsText(chunk.getTenantId(), currentUser.getTenantId())) {
            return false;
        }
        if (documentInfo.getCurrentVersion() != chunk.getVersion()) {
            return false;
        }
        return canAccess(
                currentUser,
                chunk.getOwnerId(),
                chunk.getDepartmentId(),
                chunk.getVisibility(),
                chunk.getAllowedUserIds(),
                chunk.getAllowedRoleIds()
        );
    }

    public static boolean canAccess(
            CurrentUser currentUser,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds
    ) {
        if (currentUser == null || visibility == null) {
            return false;
        }
        return switch (visibility) {
            case PRIVATE -> equalsText(currentUser.getUserId(), ownerId);
            case DEPARTMENT -> currentUser.getDepartmentIds().contains(departmentId);
            case TENANT, PUBLIC -> true;
            case CUSTOM -> containsAny(allowedUserIds, Set.of(currentUser.getUserId()))
                    || containsAny(allowedRoleIds, currentUser.getRoleIds());
        };
    }

    public DocumentIngestTask getIngestStatus(String documentId) {
        DocumentIngestTask task = ingestTaskService.getByDocumentId(documentId);
        if (task == null) {
            throw new DocumentException(ERROR_DOCUMENT_NOT_FOUND, "文档入库任务不存在：" + documentId);
        }
        return task;
    }

    private Map<DocumentChunk, List<Double>> embedChunks(String documentId, List<DocumentChunk> chunks) {
        Map<DocumentChunk, List<Double>> embeddings = new HashMap<>();
        for (DocumentChunk chunk : chunks) {
            long startNanos = System.nanoTime();
            List<Double> vector = embeddingClient.embed(chunk.getContent());
            embeddings.put(chunk, List.copyOf(vector));
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
        List<String> deletedChunkIds = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk.getStatus() == DocumentStatus.ACTIVE) {
                deletedChunkCount++;
            }
            chunk.setStatus(DocumentStatus.DELETED);
            chunk.setDocumentStatus(DocumentStatus.DELETED);
            deletedChunkIds.add(chunk.getChunkId());
        }
        vectorStore.deleteByChunkIds(deletedChunkIds);
        return deletedChunkCount;
    }

    private void cleanupVersionArtifacts(String documentId, int version) {
        List<DocumentChunk> chunks = new ArrayList<>(documentChunkStore.getOrDefault(documentId, List.of()));
        if (chunks.isEmpty()) {
            return;
        }
        List<String> deletedChunkIds = new ArrayList<>();
        List<DocumentChunk> retainedChunks = new ArrayList<>();
        for (DocumentChunk chunk : chunks) {
            if (chunk.getVersion() == version) {
                chunk.setStatus(DocumentStatus.DELETED);
                chunk.setDocumentStatus(DocumentStatus.DELETED);
                deletedChunkIds.add(chunk.getChunkId());
            } else {
                retainedChunks.add(chunk);
            }
        }
        vectorStore.deleteByChunkIds(deletedChunkIds);
        documentChunkStore.put(documentId, List.copyOf(retainedChunks));
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
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
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

    private static boolean containsAny(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String value : right) {
            if (left.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsText(String left, String right) {
        return left != null && left.equals(right);
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

    private static String stableChunkId(String documentId, int version, int chunkIndex, String contentHash) {
        String hashPrefix = contentHash == null || contentHash.length() < 16 ? contentHash : contentHash.substring(0, 16);
        return documentId + "-v" + version + "-c" + chunkIndex + "-" + hashPrefix;
    }

    private static long elapsedMillis(long startNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
