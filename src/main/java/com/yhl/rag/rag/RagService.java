package com.yhl.rag.rag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.yhl.rag.llm.LlmClient;
import com.yhl.rag.llm.LlmMessage;
import org.springframework.stereotype.Service;

@Service
public class RagService {

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的知识库问答助手。
            只能依据用户提供的上下文回答问题。
            如果上下文中没有答案，请明确说“根据当前知识库资料无法回答”。
            回答要简洁，并在必要时说明依据来自哪些资料标题。
            """;

    private static final int TOP_K = 3;
    private static final int SNIPPET_LENGTH = 220;

    private final LlmClient llmClient;
    private final ConcurrentMap<String, RagDocument> documentStore = new ConcurrentHashMap<>();

    public RagService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public RagDocument addDocument(String title, String content) {
        String id = UUID.randomUUID().toString();
        RagDocument document = new RagDocument(id, title.trim(), content.trim(), Instant.now());
        documentStore.put(id, document);
        return document;
    }

    public List<RagDocument> listDocuments() {
        return documentStore.values().stream()
                .sorted(Comparator.comparing(RagDocument::getCreatedAt).reversed())
                .toList();
    }

    public RagQueryResponse query(String question) {
        List<ScoredDocument> matchedDocuments = retrieve(question);
        List<RagSource> sources = matchedDocuments.stream()
                .map(scored -> new RagSource(
                        scored.document().getId(),
                        scored.document().getTitle(),
                        scored.score(),
                        snippet(scored.document().getContent())
                ))
                .toList();

        if (sources.isEmpty()) {
            return new RagQueryResponse("根据当前知识库资料无法回答。", List.of());
        }

        String userPrompt = """
                问题：
                %s

                知识库上下文：
                %s
                """.formatted(question, buildContext(matchedDocuments));

        String answer = llmClient.generate(
                SYSTEM_PROMPT,
                List.of(new LlmMessage("user", userPrompt))
        );
        return new RagQueryResponse(answer, sources);
    }

    private List<ScoredDocument> retrieve(String question) {
        Set<String> terms = tokenize(question);
        if (terms.isEmpty()) {
            return List.of();
        }

        return documentStore.values().stream()
                .map(document -> new ScoredDocument(document, score(document, terms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed()
                        .thenComparing(scored -> scored.document().getCreatedAt(), Comparator.reverseOrder()))
                .limit(TOP_K)
                .toList();
    }

    private static int score(RagDocument document, Set<String> terms) {
        String title = normalize(document.getTitle());
        String content = normalize(document.getContent());
        int score = 0;

        for (String term : terms) {
            if (title.contains(term)) {
                score += 3;
            }
            if (content.contains(term)) {
                score += 1;
            }
        }

        return score;
    }

    private static Set<String> tokenize(String text) {
        String normalized = normalize(text);
        Set<String> terms = new LinkedHashSet<>();
        StringBuilder latinOrDigit = new StringBuilder();

        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint) && !isCjk(codePoint)) {
                latinOrDigit.appendCodePoint(codePoint);
            } else {
                addLatinOrDigitTerm(terms, latinOrDigit);
                if (isCjk(codePoint)) {
                    terms.add(new String(Character.toChars(codePoint)));
                }
            }
            offset += Character.charCount(codePoint);
        }
        addLatinOrDigitTerm(terms, latinOrDigit);

        return terms;
    }

    private static void addLatinOrDigitTerm(Set<String> terms, StringBuilder term) {
        if (term.length() >= 2) {
            terms.add(term.toString());
        }
        term.setLength(0);
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase();
    }

    private static String buildContext(List<ScoredDocument> matchedDocuments) {
        List<String> sections = new ArrayList<>();
        for (int index = 0; index < matchedDocuments.size(); index++) {
            RagDocument document = matchedDocuments.get(index).document();
            sections.add("""
                    [%d] 标题：%s
                    内容：%s
                    """.formatted(index + 1, document.getTitle(), snippet(document.getContent())));
        }
        return String.join("\n", sections);
    }

    private static String snippet(String content) {
        if (content == null || content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        return content.substring(0, SNIPPET_LENGTH) + "...";
    }

    private record ScoredDocument(RagDocument document, int score) {
    }
}
