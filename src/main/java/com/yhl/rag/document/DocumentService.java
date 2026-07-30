package com.yhl.rag.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.yhl.rag.chunk.ChunkConfig;
import com.yhl.rag.chunk.ChunkResult;
import com.yhl.rag.chunk.ChunkStrategy;
import com.yhl.rag.chunk.ChunkingService;
import com.yhl.rag.chunk.InMemoryParentStore;
import com.yhl.rag.chunk.ParentBlock;
import com.yhl.rag.chunk.ParentStore;
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
    private final ChunkingService chunkingService;
    private final ParentStore parentStore;
    private final ContextualEnricher contextualEnricher;
    private final PdfParser pdfParser;
    private final ImageStore imageStore;
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
            KnowledgeBaseVersionService knowledgeBaseVersionService,
            ChunkingService chunkingService,
            ParentStore parentStore,
            ContextualEnricher contextualEnricher,
            PdfParser pdfParser,
            ImageStore imageStore
    ) {
        this.ragProperties = ragProperties;
        this.llmProperties = llmProperties;
        this.embeddingClient = embeddingClient;
        this.currentUserProvider = currentUserProvider;
        this.vectorStore = vectorStore;
        this.ingestTaskService = ingestTaskService;
        this.knowledgeBaseVersionService = knowledgeBaseVersionService;
        this.chunkingService = chunkingService;
        this.parentStore = parentStore;
        this.contextualEnricher = contextualEnricher;
        this.pdfParser = pdfParser;
        this.imageStore = imageStore;
    }

    // 无 PdfParser/ImageStore 重载：存量测试沿用，默认手写解析器 + 内存图片存储。
    public DocumentService(
            RagProperties ragProperties,
            LlmProperties llmProperties,
            EmbeddingClient embeddingClient,
            MockCurrentUserProvider currentUserProvider,
            VectorStore vectorStore,
            DocumentIngestTaskService ingestTaskService,
            KnowledgeBaseVersionService knowledgeBaseVersionService,
            ChunkingService chunkingService,
            ParentStore parentStore,
            ContextualEnricher contextualEnricher
    ) {
        this(
                ragProperties,
                llmProperties,
                embeddingClient,
                currentUserProvider,
                vectorStore,
                ingestTaskService,
                knowledgeBaseVersionService,
                chunkingService,
                parentStore,
                contextualEnricher,
                new PdfParser(),
                new ImageStore()
        );
    }

    // 无 ContextualEnricher 重载：存量测试沿用，默认无 LlmClient 的 enricher（contextual 关时永不调用）。
    public DocumentService(
            RagProperties ragProperties,
            LlmProperties llmProperties,
            EmbeddingClient embeddingClient,
            MockCurrentUserProvider currentUserProvider,
            VectorStore vectorStore,
            DocumentIngestTaskService ingestTaskService,
            KnowledgeBaseVersionService knowledgeBaseVersionService,
            ChunkingService chunkingService,
            ParentStore parentStore
    ) {
        this(
                ragProperties,
                llmProperties,
                embeddingClient,
                currentUserProvider,
                vectorStore,
                ingestTaskService,
                knowledgeBaseVersionService,
                chunkingService,
                parentStore,
                new ContextualEnricher(null, ragProperties)
        );
    }

    public DocumentService(
            RagProperties ragProperties,
            LlmProperties llmProperties,
            EmbeddingClient embeddingClient,
            MockCurrentUserProvider currentUserProvider,
            VectorStore vectorStore,
            DocumentIngestTaskService ingestTaskService,
            KnowledgeBaseVersionService knowledgeBaseVersionService
    ) {
        this(
                ragProperties,
                llmProperties,
                embeddingClient,
                currentUserProvider,
                vectorStore,
                ingestTaskService,
                knowledgeBaseVersionService,
                new ChunkingService(embeddingClient),
                new InMemoryParentStore()
        );
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
        ParsedContent parsed = parseRawContent(readBytes(file), filename, contentType);
        String content = parsed.text();
        DocumentVisibility visibility = existingDocument.getVisibility();

        ChunkResult chunkResult = splitForIngest(
                documentId,
                filename,
                content,
                newVersion,
                existingDocument.getTenantId(),
                existingDocument.getOwnerId(),
                existingDocument.getDepartmentId(),
                visibility,
                existingDocument.getAllowedUserIds(),
                existingDocument.getAllowedRoleIds(),
                existingDocument.getPermissionLevel()
        );
        List<DocumentChunk> newChunks = new ArrayList<>(chunkResult.children());
        newChunks.addAll(buildImageChunks(
                documentId,
                filename,
                parsed.images(),
                newVersion,
                existingDocument.getTenantId(),
                existingDocument.getOwnerId(),
                existingDocument.getDepartmentId(),
                visibility,
                existingDocument.getAllowedUserIds(),
                existingDocument.getAllowedRoleIds(),
                existingDocument.getPermissionLevel(),
                newChunks.size()
        ));
        Map<DocumentChunk, List<Double>> embeddings = embedChunks(documentId, newChunks, chunkResult.parents(), content);

        int deletedChunkCount = deactivateChunksAndEmbeddings(oldChunks);
        parentStore.deleteByDocumentIdAndVersion(documentId, oldVersion);
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
        parentStore.saveAll(chunkResult.parents());
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
        ParsedContent parsed = parseRawContent(rawContent, documentInfo.getFilename(), documentInfo.getContentType());
        String content = parsed.text();

        ingestTaskService.updateCurrentStep(task.getTaskId(), DocumentIngestStep.CHUNK);
        ChunkResult chunkResult = splitForIngest(
                documentInfo.getId(),
                documentInfo.getFilename(),
                content,
                documentInfo.getVersion(),
                documentInfo.getTenantId(),
                documentInfo.getOwnerId(),
                documentInfo.getDepartmentId(),
                documentInfo.getVisibility(),
                documentInfo.getAllowedUserIds(),
                documentInfo.getAllowedRoleIds(),
                documentInfo.getPermissionLevel()
        );
        List<DocumentChunk> chunks = new ArrayList<>(chunkResult.children());
        chunks.addAll(buildImageChunks(
                documentInfo.getId(),
                documentInfo.getFilename(),
                parsed.images(),
                documentInfo.getVersion(),
                documentInfo.getTenantId(),
                documentInfo.getOwnerId(),
                documentInfo.getDepartmentId(),
                documentInfo.getVisibility(),
                documentInfo.getAllowedUserIds(),
                documentInfo.getAllowedRoleIds(),
                documentInfo.getPermissionLevel(),
                chunks.size()
        ));
        for (DocumentChunk chunk : chunks) {
            chunk.setDocumentStatus(DocumentStatus.READY);
        }
        if (chunks.isEmpty()) {
            throw new DocumentException(ERROR_EMPTY_FILE, "文档内容为空，无法生成 chunk");
        }

        ingestTaskService.updateCurrentStep(task.getTaskId(), DocumentIngestStep.EMBEDDING);
        Map<DocumentChunk, List<Double>> embeddings = embedChunks(documentInfo.getId(), chunks, chunkResult.parents(), content);
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
        parentStore.saveAll(chunkResult.parents());
        documentInfo.setStatus(DocumentStatus.READY);
        long knowledgeBaseVersion = knowledgeBaseVersionService.incrementAndGet();

        log.info("document_ingest_processed documentId={} taskId={} textChars={} chunkCount={} parentCount={} knowledgeBaseVersion={}",
                documentInfo.getId(),
                task.getTaskId(),
                content.length(),
                chunks.size(),
                chunkResult.parents().size(),
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
        parentStore.deleteByDocumentId(documentId);
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
        // 公开 chunkText 维持 FIXED 行为不变（不读 strategy），存量调用方与测试零回归。
        ChunkConfig config = new ChunkConfig(
                ChunkStrategy.FIXED,
                chunkSize,
                overlap,
                ragProperties.getChunk().getSemantic().getThreshold(),
                version,
                tenantId,
                ownerId,
                departmentId,
                visibility,
                allowedUserIds,
                allowedRoleIds,
                permissionLevel
        );
        int originalTextLength = text == null ? 0 : text.length();
        List<DocumentChunk> chunks = chunkingService.split(documentId, filename, text, config).children();
        log.info("document_chunk documentId={} textChars={} chunkSize={} overlap={} chunkCount={}",
                documentId,
                originalTextLength,
                chunkSize,
                overlap,
                chunks.size());
        return chunks;
    }

    /** 入库路径分块：按配置 strategy 取 splitter，产出子块 + 父块；公开 chunkText 仍固定走 FIXED。 */
    private ChunkResult splitForIngest(
            String documentId,
            String filename,
            String text,
            int version,
            String tenantId,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds,
            int permissionLevel
    ) {
        ChunkStrategy strategy = ragProperties.getChunk().getStrategy();
        ChunkConfig config = new ChunkConfig(
                strategy,
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                ragProperties.getChunk().getSemantic().getThreshold(),
                version,
                tenantId,
                ownerId,
                departmentId,
                visibility,
                allowedUserIds,
                allowedRoleIds,
                permissionLevel
        );
        int originalTextLength = text == null ? 0 : text.length();
        ChunkResult result = chunkingService.split(documentId, filename, text, config);
        log.info("document_chunk documentId={} strategy={} textChars={} chunkSize={} overlap={} chunkCount={} parentCount={}",
                documentId,
                strategy,
                originalTextLength,
                ragProperties.getChunkSize(),
                ragProperties.getChunkOverlap(),
                result.children().size(),
                result.parents().size());
        return result;
    }

    /** 按类型解析原始字节：图片 → 单张 IMAGE；PDF → 文本 + 内嵌图；其余 → UTF-8 文本。多模态关时一律按文本。 */
    private ParsedContent parseRawContent(byte[] raw, String filename, String contentType) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String type = normalizeContentType(contentType);
        if (ragProperties.getMultimodal().isEnabled()) {
            if (isImageFilename(lower) || isImageContentType(type)) {
                // 整张图片作为一个 IMAGE chunk；content 为空，向量来自图像本身（page=0 表示非 PDF 来源）。
                return new ParsedContent("", List.of(new PdfParser.ExtractedImage(raw, imageMimeType(lower, type), 0)));
            }
            if (isPdfFilename(lower) || isPdfContentType(type)) {
                PdfParser.PdfParseResult result = pdfParser.parse(raw);
                return new ParsedContent(result.text(), result.images());
            }
        }
        return new ParsedContent(new String(raw, StandardCharsets.UTF_8), List.of());
    }

    /** 把抽取的图片落入 ImageStore 并建成 IMAGE chunk；chunkIndex 接在文本子块之后。 */
    private List<DocumentChunk> buildImageChunks(
            String documentId,
            String filename,
            List<PdfParser.ExtractedImage> images,
            int version,
            String tenantId,
            String ownerId,
            String departmentId,
            DocumentVisibility visibility,
            Set<String> allowedUserIds,
            Set<String> allowedRoleIds,
            int permissionLevel,
            int indexOffset
    ) {
        if (images.isEmpty()) {
            return List.of();
        }
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = indexOffset;
        for (PdfParser.ExtractedImage image : images) {
            String imageRef = imageStore.put(image.bytes(), image.mimeType());
            DocumentChunk chunk = new DocumentChunk(
                    documentId + "::image::v" + version + "::" + index,
                    documentId,
                    filename,
                    imageCaption(filename, image),
                    null,
                    index,
                    Instant.now(),
                    tenantId,
                    ownerId,
                    departmentId,
                    visibility,
                    allowedUserIds,
                    allowedRoleIds,
                    DocumentStatus.ACTIVE,
                    version,
                    permissionLevel
            );
            chunk.setModality(Modality.IMAGE);
            chunk.setImageRef(imageRef);
            chunks.add(chunk);
            index++;
        }
        return chunks;
    }

    /** IMAGE chunk 的展示文本（无 OCR）：供 sources 预览与 BM25 命中，向量仍来自图像本身。 */
    private static String imageCaption(String filename, PdfParser.ExtractedImage image) {
        return image.page() > 0
                ? "[图片] " + filename + " 第" + image.page() + "页"
                : "[图片] " + filename;
    }

    private record ParsedContent(String text, List<PdfParser.ExtractedImage> images) {
    }

    public List<DocumentInfo> listDocuments() {
        return documentInfoStore.values().stream()
                .sorted(Comparator.comparing(DocumentInfo::getCreatedAt).reversed())
                .toList();
    }

    public String getDocumentText(String id) {
        return documentTextStore.get(id);
    }

    /** 按 imageRef 取回 IMAGE chunk 的原始图片字节，供展示/回填（无 ref 或已清理返回空）。 */
    public java.util.Optional<ImageStore.StoredImage> getImage(String imageRef) {
        return imageStore.get(imageRef);
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

    private Map<DocumentChunk, List<Double>> embedChunks(
            String documentId,
            List<DocumentChunk> chunks,
            List<ParentBlock> parents,
            String fullText
    ) {
        // Contextual Retrieval：上下文源优先取子块所属父块正文，无父块则退回全文；enricher 负责开关与降级。
        Map<String, String> parentContentById = new HashMap<>();
        for (ParentBlock parent : parents) {
            parentContentById.put(parent.getParentId(), parent.getContent());
        }
        Map<DocumentChunk, List<Double>> embeddings = new HashMap<>();
        for (DocumentChunk chunk : chunks) {
            long startNanos = System.nanoTime();
            List<Double> vector;
            if (chunk.getModality() == Modality.IMAGE) {
                // 图像 chunk 走 VL 图像 embedding，进与文本同一向量空间（真多模态）。
                ImageStore.StoredImage image = imageStore.get(chunk.getImageRef())
                        .orElseThrow(() -> new DocumentException(ERROR_EMPTY_FILE, "图像引用丢失：" + chunk.getImageRef()));
                vector = embeddingClient.embedImage(image.bytes(), image.mimeType());
            } else {
                // Contextual Retrieval：上下文源优先取子块所属父块正文，无父块则退回全文；enricher 负责开关与降级。
                String contextSource = parentContentById.getOrDefault(chunk.getParentId(), fullText);
                String embeddingText = contextualEnricher.buildEmbeddingText(chunk.getContent(), contextSource);
                vector = embeddingClient.embed(embeddingText);
            }
            embeddings.put(chunk, List.copyOf(vector));
            log.info("document_chunk_embedding documentId={} chunkId={} modality={} model={} vectorDimension={} durationMs={}",
                    documentId,
                    chunk.getChunkId(),
                    chunk.getModality(),
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
            releaseImage(chunk);
        }
        vectorStore.deleteByChunkIds(deletedChunkIds);
        return deletedChunkCount;
    }

    private void releaseImage(DocumentChunk chunk) {
        if (chunk.getModality() == Modality.IMAGE && chunk.getImageRef() != null) {
            imageStore.remove(chunk.getImageRef());
        }
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
                releaseImage(chunk);
            } else {
                retainedChunks.add(chunk);
            }
        }
        vectorStore.deleteByChunkIds(deletedChunkIds);
        parentStore.deleteByDocumentIdAndVersion(documentId, version);
        documentChunkStore.put(documentId, List.copyOf(retainedChunks));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentException(ERROR_EMPTY_FILE, "上传文件不能为空");
        }

        boolean multimodal = ragProperties.getMultimodal().isEnabled();
        String originalFilename = file.getOriginalFilename();
        String filename = safeFilename(originalFilename);
        String contentType = normalizeContentType(file.getContentType());
        String message = multimodal
                ? "仅支持上传 txt、md、markdown、pdf、png、jpg 文件"
                : "仅支持上传 txt、md、markdown 文件";

        if (StringUtils.hasText(originalFilename) && !isSupportedFilename(filename, multimodal)) {
            throw new DocumentException(ERROR_UNSUPPORTED_TYPE, message);
        }

        if (!StringUtils.hasText(originalFilename) && !isSupportedContentType(contentType, multimodal)) {
            throw new DocumentException(ERROR_UNSUPPORTED_TYPE, message);
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

    private static boolean isSupportedFilename(String filename, boolean multimodal) {
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        boolean text = lowerFilename.endsWith(".txt")
                || lowerFilename.endsWith(".md")
                || lowerFilename.endsWith(".markdown");
        return text || (multimodal && (isPdfFilename(lowerFilename) || isImageFilename(lowerFilename)));
    }

    private static boolean isSupportedContentType(String contentType, boolean multimodal) {
        boolean text = contentType.startsWith("text/plain")
                || contentType.startsWith("text/markdown")
                || contentType.startsWith("text/x-markdown")
                || "application/octet-stream".equals(contentType);
        return text || (multimodal && (isPdfContentType(contentType) || isImageContentType(contentType)));
    }

    private static boolean isPdfFilename(String lowerFilename) {
        return lowerFilename.endsWith(".pdf");
    }

    private static boolean isImageFilename(String lowerFilename) {
        return lowerFilename.endsWith(".png")
                || lowerFilename.endsWith(".jpg")
                || lowerFilename.endsWith(".jpeg");
    }

    private static boolean isPdfContentType(String contentType) {
        return contentType.startsWith("application/pdf");
    }

    private static boolean isImageContentType(String contentType) {
        return contentType.startsWith("image/png")
                || contentType.startsWith("image/jpeg")
                || contentType.startsWith("image/jpg");
    }

    private static String imageMimeType(String lowerFilename, String contentType) {
        if (isImageContentType(contentType)) {
            return contentType;
        }
        if (lowerFilename.endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
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

    private static long elapsedMillis(long startNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
