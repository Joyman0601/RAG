package com.yhl.rag.cost;

import java.util.List;

import com.yhl.rag.llm.LlmMessage;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int chineseChars = 0;
        int otherChars = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (isCjk(ch)) {
                chineseChars++;
            } else if (!Character.isWhitespace(ch)) {
                otherChars++;
            }
        }
        int chineseTokens = (int) Math.ceil(chineseChars / 1.5);
        int otherTokens = (int) Math.ceil(otherChars / 4.0);
        return Math.max(1, chineseTokens + otherTokens);
    }

    public int estimateMessages(String instructions, List<LlmMessage> messages) {
        int tokens = estimate(instructions);
        if (messages != null) {
            for (LlmMessage message : messages) {
                tokens += estimate(message == null ? null : message.content());
            }
        }
        return tokens;
    }

    private static boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }
}
