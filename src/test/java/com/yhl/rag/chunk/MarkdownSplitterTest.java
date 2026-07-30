package com.yhl.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.yhl.rag.document.DocumentChunk;
import com.yhl.rag.document.DocumentVisibility;
import org.junit.jupiter.api.Test;

class MarkdownSplitterTest {

    private final MarkdownSplitter splitter = new MarkdownSplitter();

    @Test
    void split_byHeadings_makesLeafSectionParentsWithBreadcrumbChildren() {
        String text = "# 安装\n前言段落。\n## 环境要求\n需要 JDK 17。\n## 下载\n从官网下载。\n";

        ChunkResult result = splitter.split("doc-1", "guide.md", text, config(1000, 0));

        assertThat(result.parents()).extracting(ParentBlock::getContent)
                .containsExactly("前言段落。", "需要 JDK 17。", "从官网下载。");
        assertThat(result.children()).hasSize(3);

        DocumentChunk envChild = result.children().get(1);
        assertThat(envChild.getContent()).isEqualTo("标题：安装 > 环境要求\n需要 JDK 17。");
        assertThat(envChild.getParentId()).isEqualTo(result.parents().get(1).getParentId());

        // 每个子块都挂在对应 section 的父块上。
        for (int i = 0; i < result.children().size(); i++) {
            assertThat(result.children().get(i).getParentId())
                    .isEqualTo(result.parents().get(i).getParentId());
        }
    }

    @Test
    void split_oversizedSection_fallsBackToMultipleChildrenSharingParent() {
        String text = "# 长\n一二三四五六七八九十";

        ChunkResult result = splitter.split("doc-1", "guide.md", text, config(5, 0));

        assertThat(result.parents()).singleElement()
                .satisfies(parent -> assertThat(parent.getContent()).isEqualTo("一二三四五六七八九十"));
        List<DocumentChunk> children = result.children();
        assertThat(children).hasSize(2);
        assertThat(children).extracting(DocumentChunk::getContent)
                .containsExactly("标题：长\n一二三四五", "标题：长\n六七八九十");
        assertThat(children).extracting(DocumentChunk::getParentId)
                .containsOnly(result.parents().get(0).getParentId());
    }

    @Test
    void split_noHeadings_treatsWholeTextAsOneSection() {
        ChunkResult result = splitter.split("doc-1", "plain.md", "没有任何标题的正文。", config(1000, 0));

        assertThat(result.parents()).singleElement()
                .satisfies(parent -> assertThat(parent.getContent()).isEqualTo("没有任何标题的正文。"));
        // 无标题时不加面包屑前缀。
        assertThat(result.children()).singleElement()
                .satisfies(child -> assertThat(child.getContent()).isEqualTo("没有任何标题的正文。"));
    }

    @Test
    void split_containerHeadingWithoutBody_producesNoParent() {
        String text = "# 顶层\n## 子节\n子节正文。";

        ChunkResult result = splitter.split("doc-1", "guide.md", text, config(1000, 0));

        // 顶层只有子标题、无自身正文 → 不产父块；只有子节这一个有正文的 section。
        assertThat(result.parents()).singleElement()
                .satisfies(parent -> assertThat(parent.getContent()).isEqualTo("子节正文。"));
        assertThat(result.children()).singleElement()
                .satisfies(child -> assertThat(child.getContent()).isEqualTo("标题：顶层 > 子节\n子节正文。"));
    }

    private static ChunkConfig config(int chunkSize, int overlap) {
        return new ChunkConfig(
                ChunkStrategy.MARKDOWN,
                chunkSize,
                overlap,
                0.6,
                1,
                "tenant-default",
                "owner-1",
                "dept-1",
                DocumentVisibility.DEPARTMENT,
                Set.of(),
                Set.of(),
                0
        );
    }
}
