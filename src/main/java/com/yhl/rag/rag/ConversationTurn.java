package com.yhl.rag.rag;

/** 一轮会话：用户问题 + 助手回答。供多轮指代消解改写与历史压缩使用。 */
public record ConversationTurn(String userMessage, String assistantMessage) {
}
