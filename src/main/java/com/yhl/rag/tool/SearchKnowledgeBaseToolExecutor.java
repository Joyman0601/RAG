package com.yhl.rag.tool;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yhl.rag.rag.RagProperties;
import com.yhl.rag.rag.RagSearchOutcome;
import com.yhl.rag.rag.RagSearchResult;
import com.yhl.rag.rag.RagSearchService;
import com.yhl.rag.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SearchKnowledgeBaseToolExecutor implements ToolExecutor<SearchKnowledgeToolRequest> {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeBaseToolExecutor.class);

    private static final String TOOL_NAME = "search_knowledge_base";
    private static final int MAX_CONTEXT_CONTENT_CHARS = 500;

    private final RagSearchService ragSearchService;
    private final RagProperties ragProperties;
    private final ToolDefinition definition;

    public SearchKnowledgeBaseToolExecutor(
            RagSearchService ragSearchService,
            RagProperties ragProperties,
            ObjectMapper objectMapper
    ) {
        this.ragSearchService = ragSearchService;
        this.ragProperties = ragProperties;
        this.definition = new ToolDefinition(
                TOOL_NAME,
                "Search enterprise knowledge base, policy documents, product documentation, or internal materials. Do not use for orders, user privacy, or real-time business data.",
                buildParameterSchema(objectMapper),
                "knowledge:search",
                RiskLevel.LOW
        );
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public Class<SearchKnowledgeToolRequest> getRequestClass() {
        return SearchKnowledgeToolRequest.class;
    }

    @Override
    public Object execute(SearchKnowledgeToolRequest request, ToolExecutionContext context) {
        CurrentUser currentUser = new CurrentUser(
                context.getUserId(),
                context.getDepartment(),
                context.getPermissionLevel()
        );
        RagSearchOutcome outcome = ragSearchService.searchWithMetrics(request.getQuery(), currentUser, false);
        List<RagSearchResult> results = outcome.results();
        List<SearchKnowledgeToolResult.Context> contexts = results.stream()
                .map(this::toContext)
                .toList();
        List<SearchKnowledgeToolResult.Source> sources = toSources(results);
        List<String> sourceIds = sources.stream()
                .map(SearchKnowledgeToolResult.Source::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        log.info("knowledge_tool_search requestId={} userId={} retrievedCount={} topK={} scoreThreshold={} sourceIds={}",
                context.getRequestId(),
                context.getUserId(),
                results.size(),
                ragProperties.getSearch().getTopK(),
                ragProperties.getSearch().getScoreThreshold(),
                sourceIds);

        return new SearchKnowledgeToolResult(!contexts.isEmpty(), contexts, sources, contexts.size());
    }

    private SearchKnowledgeToolResult.Context toContext(RagSearchResult result) {
        return new SearchKnowledgeToolResult.Context(
                limitContent(result.getContent()),
                result.getChunkId(),
                result.getDocumentId(),
                result.getFilename(),
                result.getScore()
        );
    }

    private SearchKnowledgeToolResult.Source toSource(RagSearchResult result) {
        return new SearchKnowledgeToolResult.Source(
                result.getDocumentId(),
                result.getFilename(),
                result.getChunkIndex()
        );
    }

    private List<SearchKnowledgeToolResult.Source> toSources(List<RagSearchResult> results) {
        Map<String, SearchKnowledgeToolResult.Source> sources = new LinkedHashMap<>();
        for (RagSearchResult result : results) {
            String key = result.getDocumentId() + ":" + result.getChunkIndex();
            sources.putIfAbsent(key, toSource(result));
        }
        return List.copyOf(sources.values());
    }

    private static String limitContent(String content) {
        if (content == null || content.length() <= MAX_CONTEXT_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTEXT_CONTENT_CHARS);
    }

    private com.fasterxml.jackson.databind.JsonNode buildParameterSchema(ObjectMapper objectMapper) {
        com.fasterxml.jackson.databind.node.ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        com.fasterxml.jackson.databind.node.ObjectNode properties = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode query = objectMapper.createObjectNode();
        query.put("type", "string");
        query.put("description", "Knowledge base search query. Do not include userId, tenantId, topK, or scoreThreshold.");
        query.put("maxLength", 500);
        properties.set("query", query);

        schema.set("properties", properties);
        schema.putArray("required").add("query");
        return schema;
    }
}
