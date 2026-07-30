package com.yhl.rag.demo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.yhl.rag.document.DocumentInfo;
import com.yhl.rag.document.DocumentService;
import com.yhl.rag.document.DocumentUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地演示环境启动时自动灌入脱敏 seed 文档到内存版知识库。
 *
 * <p>仅在满足以下条件时生效（任何一条不满足则跳过）：
 * <ul>
 *   <li>Profile 为 dev / local / default（生产 pgvector profile 不加载）</li>
 *   <li>rag.demo.seed-enabled=true（默认 true）</li>
 *   <li>启动时 DocumentService.listDocuments() 为空（避免重复灌）</li>
 * </ul>
 *
 * <p>seed 文件放在 classpath:/seed/*.md。走标准 upload 通路，
 * IngestWorker 会在 3-6 秒内异步消费入库。
 */
@Profile({"dev", "local", "default"})
@Component
public class DemoSeedLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedLoader.class);
    private static final String SEED_PATTERN = "classpath:/seed/*.md";

    private final DocumentService documentService;
    private final boolean seedEnabled;

    public DemoSeedLoader(
            DocumentService documentService,
            @Value("${rag.demo.seed-enabled:true}") boolean seedEnabled
    ) {
        this.documentService = documentService;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("demo_seed_skip reason=disabled");
            return;
        }
        List<DocumentInfo> existing = documentService.listDocuments();
        if (!existing.isEmpty()) {
            log.info("demo_seed_skip reason=documents_exist count={}", existing.size());
            return;
        }

        Resource[] resources;
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            resources = resolver.getResources(SEED_PATTERN);
        } catch (IOException e) {
            log.warn("demo_seed_load_failed error={}", e.getMessage());
            return;
        }
        if (resources.length == 0) {
            log.info("demo_seed_skip reason=no_seed_files_found pattern={}", SEED_PATTERN);
            return;
        }

        int loaded = 0;
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) continue;
            try {
                byte[] content;
                try (InputStream in = resource.getInputStream()) {
                    content = in.readAllBytes();
                }
                DocumentUploadResponse resp = documentService.upload(new InMemoryMarkdownMultipartFile(filename, content));
                loaded++;
                log.info("demo_seed_upload_accepted filename={} documentId={} taskId={} size={}",
                        filename, resp.getDocumentId(), resp.getTaskId(), content.length);
            } catch (Exception e) {
                log.warn("demo_seed_upload_failed filename={} error={}", filename, e.getMessage());
            }
        }
        log.info("demo_seed_done loaded={} total={} note=ingest_worker_will_process_asynchronously_within_seconds",
                loaded, resources.length);
    }

    /**
     * 极简 MultipartFile，仅承载文件名 + 字节内容 + text/markdown MIME。
     * 用于绕过 test scope 的 MockMultipartFile。
     */
    private static final class InMemoryMarkdownMultipartFile implements MultipartFile {

        private final String filename;
        private final byte[] content;

        InMemoryMarkdownMultipartFile(String filename, byte[] content) {
            this.filename = filename;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return "text/markdown";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(Path dest) throws IOException {
            Files.write(dest, content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            try (OutputStream out = Files.newOutputStream(dest.toPath())) {
                out.write(content);
            }
        }

        @Override
        public String toString() {
            return "InMemoryMarkdownMultipartFile{filename=" + filename + ", size=" + content.length + "}";
        }
    }
}
