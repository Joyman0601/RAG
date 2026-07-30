package com.yhl.rag.chunk;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.yhl.rag.document.DocumentChunk;
import org.springframework.stereotype.Component;

/**
 * Markdown 结构感知分块：按 #/##/… 标题切 section，有正文的 section = 一个独立父块；
 * 子块在 section 内按固定窗口切，并在正文前加标题面包屑（"标题：A > B\n正文"）帮助检索定位。
 * 纯容器标题（只有子标题、无自身正文）不产父块；无标题文档整体作一个 section。
 */
@Component
public class MarkdownSplitter implements TextSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    @Override
    public ChunkStrategy strategy() {
        return ChunkStrategy.MARKDOWN;
    }

    @Override
    public ChunkResult split(String documentId, String filename, String text, ChunkConfig config) {
        Chunks.validateConfig(config);
        if (text == null || text.isBlank()) {
            return ChunkResult.childrenOnly(List.of());
        }

        Instant createdAt = Instant.now();
        List<ParentBlock> parents = new ArrayList<>();
        List<DocumentChunk> children = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        List<Integer> levelStack = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int[] counters = {0, 0}; // [0]=chunkIndex, [1]=sectionIndex

        for (String line : text.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                flushSection(documentId, filename, headingStack, body, config, createdAt, parents, children, counters);
                int level = matcher.group(1).length();
                while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1) >= level) {
                    levelStack.remove(levelStack.size() - 1);
                    headingStack.remove(headingStack.size() - 1);
                }
                levelStack.add(level);
                headingStack.add(matcher.group(2).trim());
            } else {
                body.append(line).append('\n');
            }
        }
        flushSection(documentId, filename, headingStack, body, config, createdAt, parents, children, counters);

        return new ChunkResult(children, parents);
    }

    private void flushSection(
            String documentId,
            String filename,
            List<String> headingStack,
            StringBuilder body,
            ChunkConfig config,
            Instant createdAt,
            List<ParentBlock> parents,
            List<DocumentChunk> children,
            int[] counters
    ) {
        String sectionBody = body.toString().trim();
        body.setLength(0);
        if (sectionBody.isEmpty()) {
            return;
        }

        int sectionIndex = counters[1]++;
        String parentId = ChunkIds.stableParentId(documentId, config.version(), sectionIndex);
        parents.add(new ParentBlock(
                parentId,
                documentId,
                sectionBody,
                config.version(),
                config.tenantId(),
                config.ownerId(),
                config.departmentId(),
                config.visibility(),
                config.allowedUserIds(),
                config.allowedRoleIds(),
                config.permissionLevel()
        ));

        String breadcrumb = headingStack.isEmpty() ? "" : "标题：" + String.join(" > ", headingStack) + "\n";
        for (String window : windows(sectionBody, config)) {
            String content = breadcrumb + window;
            children.add(Chunks.build(documentId, filename, content, counters[0]++, parentId, config, createdAt));
        }
    }

    /** 超长 section 回退：把 section 正文按固定窗口切成多段（共享同一 parentId）。 */
    private static List<String> windows(String text, ChunkConfig config) {
        List<String> windows = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + config.chunkSize(), text.length());
            windows.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start = end - config.overlap();
        }
        return windows;
    }
}
