package com.zhituagent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 感知句子边界的文本切分器：优先在中英文标点处断句，保留 160 字符重叠窗口
// 防止语义在 chunk 边界丢失；超长句子(>800字)回退为固定长度切分
@Component
public class DocumentSplitter {

    private static final int CHUNK_SIZE = 800;
    private static final int OVERLAP_SIZE = 160;
    private static final Pattern SENTENCE_PATTERN = Pattern.compile(".*?(?:[。！？；.!?;]|$)", Pattern.DOTALL);

    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> chunks = new ArrayList<>();
        String normalized = text.trim().replace("\r", "");
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : splitSentences(normalized)) {
            if (sentence.length() > CHUNK_SIZE) {
                flushCurrentChunk(chunks, currentChunk);
                splitOversizedSentence(chunks, sentence);
                continue;
            }

            if (currentChunk.length() > 0 && currentChunk.length() + sentence.length() > CHUNK_SIZE) {
                String completedChunk = currentChunk.toString().trim();
                chunks.add(completedChunk);
                currentChunk = new StringBuilder(overlapTail(completedChunk));
            }

            currentChunk.append(sentence);
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(text);
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private void splitOversizedSentence(List<String> chunks, String sentence) {
        for (int start = 0; start < sentence.length(); start += CHUNK_SIZE - OVERLAP_SIZE) {
            int end = Math.min(sentence.length(), start + CHUNK_SIZE);
            chunks.add(sentence.substring(start, end).trim());
            if (end >= sentence.length()) {
                return;
            }
        }
    }

    private void flushCurrentChunk(List<String> chunks, StringBuilder currentChunk) {
        if (!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString().trim());
            currentChunk.setLength(0);
        }
    }

    private String overlapTail(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return "";
        }
        int start = Math.max(0, chunk.length() - OVERLAP_SIZE);
        return chunk.substring(start);
    }
}
