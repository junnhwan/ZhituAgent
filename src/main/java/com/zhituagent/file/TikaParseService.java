package com.zhituagent.file;

import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import com.zhituagent.config.FileProperties;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tika-based document parser that turns an upload stream into chunked text
 * ready for embedding. Handles the polyglot file types PaiSmart supports
 * (pdf/doc/docx/xls/xlsx/ppt/pptx/txt/rtf/md/html/json/csv/eml/msg) via
 * {@link AutoDetectParser}, then applies parent-child chunking with overlap.
 *
 * <p>Two safety rails:
 * <ul>
 *   <li><b>Memory guard</b> — if heap usage is above {@code
 *       zhitu.file.memory-threshold} (default 80%), force GC; if still over,
 *       throw before parsing so we don't drag the JVM into OOM under load.
 *   <li><b>HanLP fallback</b> — when a child window happens to land entirely
 *       between sentence delimiters (common in dense Chinese prose), fall back
 *       to HanLP token-aware splitting so we cut on word boundaries instead
 *       of mid-character.
 * </ul>
 */
@Service
public class TikaParseService {

    private static final Logger log = LoggerFactory.getLogger(TikaParseService.class);
    private static final int PARENT_CONTEXT_PREFIX_CHARS = 256;
    private static final int BODY_CONTENT_LIMIT_BYTES = 50 * 1024 * 1024;
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？；.!?;\\n]");

    private final FileProperties props;

    public TikaParseService(FileProperties props) {
        this.props = props;
    }

    public List<ParsedChunk> parse(InputStream stream, String filename, String contentType) {
        checkMemoryThreshold();
        String text = extractText(stream, filename, contentType);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ParsedChunk> chunks = chunkParentChild(text);
        log.info("Tika parse completed filename={} chars={} chunks={}",
                filename, text.length(), chunks.size());
        return chunks;
    }

    private String extractText(InputStream stream, String filename, String contentType) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(BODY_CONTENT_LIMIT_BYTES);
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            if (contentType != null) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            parser.parse(stream, handler, metadata, new ParseContext());
            String text = handler.toString();
            return text == null ? "" : text.trim();
        } catch (IOException | SAXException | TikaException e) {
            throw new IllegalStateException("Tika parse failed: " + filename, e);
        }
    }

    private void checkMemoryThreshold() {
        double pct = currentHeapPct();
        if (pct < props.getMemoryThreshold()) {
            return;
        }
        log.warn("Heap usage {} above threshold {}, forcing GC",
                fmtPct(pct), fmtPct(props.getMemoryThreshold()));
        System.gc();
        pct = currentHeapPct();
        if (pct >= props.getMemoryThreshold()) {
            throw new IllegalStateException(
                    "Heap usage " + fmtPct(pct) + " exceeds threshold "
                            + fmtPct(props.getMemoryThreshold())
                            + "; refusing to parse to protect process");
        }
    }

    private double currentHeapPct() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        return max == 0 ? 0.0 : (double) used / max;
    }

    private static String fmtPct(double v) {
        return String.format("%.1f%%", v * 100);
    }

    private List<ParsedChunk> chunkParentChild(String text) {
        int parentSize = props.getParentChunkSize();
        int childSize = props.getChildChunkSize();
        int overlap = props.getChunkOverlap();
        List<ParsedChunk> result = new ArrayList<>();
        for (String parent : splitByLength(text, parentSize)) {
            String parentCtx = parent.substring(0, Math.min(PARENT_CONTEXT_PREFIX_CHARS, parent.length())).trim();
            for (String child : splitChildren(parent, childSize, overlap)) {
                if (!child.isBlank()) {
                    result.add(new ParsedChunk(child.trim(), parentCtx));
                }
            }
        }
        return result;
    }

    /** Coarse parent windows, no overlap between parents. */
    private List<String> splitByLength(String text, int size) {
        if (text.length() <= size) {
            return List.of(text);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            parts.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return parts;
    }

    /** Within a parent, split into child chunks with overlap; HanLP fallback when no sentence boundaries. */
    private List<String> splitChildren(String parent, int childSize, int overlap) {
        if (parent.length() <= childSize) {
            return List.of(parent);
        }
        List<String> chunks = new ArrayList<>();
        int step = Math.max(1, childSize - overlap);
        for (int start = 0; start < parent.length(); start += step) {
            int end = Math.min(parent.length(), start + childSize);
            String chunk = parent.substring(start, end);
            if (chunk.length() == childSize && !hasSentenceBoundary(chunk)) {
                chunks.addAll(hanlpFallbackSplit(chunk, childSize));
            } else {
                chunks.add(chunk);
            }
            if (end >= parent.length()) {
                break;
            }
        }
        return chunks;
    }

    private boolean hasSentenceBoundary(String text) {
        return SENTENCE_BOUNDARY.matcher(text).find();
    }

    private List<String> hanlpFallbackSplit(String text, int targetSize) {
        try {
            List<Term> terms = StandardTokenizer.segment(text);
            if (terms == null || terms.isEmpty()) {
                return List.of(text);
            }
            List<String> result = new ArrayList<>();
            StringBuilder buf = new StringBuilder();
            for (Term term : terms) {
                String word = term.word == null ? "" : term.word;
                if (buf.length() + word.length() > targetSize && buf.length() > 0) {
                    result.add(buf.toString());
                    buf.setLength(0);
                }
                buf.append(word);
            }
            if (buf.length() > 0) {
                result.add(buf.toString());
            }
            log.debug("HanLP fallback split produced {} chunks for boundary-less {}-char input",
                    result.size(), text.length());
            return result.isEmpty() ? List.of(text) : result;
        } catch (Exception e) {
            log.warn("HanLP fallback failed for {}-char input, returning raw: {}", text.length(), e.getMessage());
            return List.of(text);
        }
    }
}
