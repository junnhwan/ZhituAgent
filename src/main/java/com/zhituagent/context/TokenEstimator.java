package com.zhituagent.context;

import java.util.ArrayList;
import java.util.List;

public class TokenEstimator {

    public long estimateMessages(List<String> inputMessages) {
        if (inputMessages == null || inputMessages.isEmpty()) {
            return 0;
        }
        List<String> safeMessages = new ArrayList<>(inputMessages);
        return safeMessages.stream()
                .mapToLong(this::estimateText)
                .sum();
    }

    public long estimateText(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        long cjkCount = text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(this::isCjk)
                .count();

        long otherCount = text.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .filter(codePoint -> !isCjk(codePoint))
                .count();

        return cjkCount + Math.max(0, Math.round(Math.ceil(otherCount / 4.0)));
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
