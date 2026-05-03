package com.zhituagent.file;

import com.zhituagent.config.FileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaParseServiceTest {

    private FileProperties props;
    private TikaParseService service;

    @BeforeEach
    void setUp() {
        props = new FileProperties();
        // Tighten chunking so tests can exercise multi-chunk paths without huge fixtures.
        props.setParentChunkSize(200);
        props.setChildChunkSize(50);
        props.setChunkOverlap(10);
        props.setMemoryThreshold(0.99); // never trip in CI
        service = new TikaParseService(props);
    }

    @Test
    void short_text_yields_single_chunk_with_self_as_parent_context() {
        InputStream in = utf8("Hello world from Tika.");

        List<ParsedChunk> chunks = service.parse(in, "greeting.txt", "text/plain");

        assertThat(chunks).hasSize(1);
        ParsedChunk only = chunks.get(0);
        assertThat(only.content()).contains("Hello world");
        assertThat(only.hasParentContext()).isTrue();
        assertThat(only.parentContext()).contains("Hello world");
    }

    @Test
    void whitespace_only_text_yields_empty_list() {
        InputStream in = utf8("   \n\t  \n  ");

        List<ParsedChunk> chunks = service.parse(in, "blank.txt", "text/plain");

        assertThat(chunks).isEmpty();
    }

    @Test
    void long_text_splits_into_multiple_child_chunks() {
        // 800 chars: triggers parent-split (200) + child-split (50, overlap 10)
        String filler = "Lorem ipsum dolor sit amet. Consectetur adipiscing elit. ".repeat(15);
        InputStream in = utf8(filler);

        List<ParsedChunk> chunks = service.parse(in, "long.txt", "text/plain");

        assertThat(chunks).hasSizeGreaterThan(4);
        chunks.forEach(c -> assertThat(c.content().length()).isLessThanOrEqualTo(60));
        // Parent context for the first chunk should come from the start of the text.
        assertThat(chunks.get(0).parentContext()).contains("Lorem");
    }

    @Test
    void boundary_less_text_uses_hanlp_fallback_to_split_on_word_boundary() {
        // 200 Chinese chars, no punctuation — pure boundary-less prose.
        String denseChinese = "智能体框架研究与实现总结".repeat(20); // 240 chars no punctuation
        InputStream in = utf8(denseChinese);

        List<ParsedChunk> chunks = service.parse(in, "dense.txt", "text/plain");

        // HanLP keeps total content roughly equal; ensure we got a usable split.
        String reassembled = String.join("", chunks.stream().map(ParsedChunk::content).toList());
        assertThat(reassembled.length()).isGreaterThanOrEqualTo(denseChinese.length() / 2);
        assertThat(chunks).hasSizeGreaterThan(1);
    }

    @Test
    void io_failure_on_input_stream_wraps_as_illegal_state() {
        // A stream that throws on read — covers the IOException branch
        // without depending on Tika's tolerance for malformed binaries.
        InputStream broken = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("simulated disk/network failure");
            }
        };

        assertThatThrownBy(() -> service.parse(broken, "any.bin", "application/octet-stream"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tika parse failed");
    }

    @Test
    void memory_threshold_zero_always_refuses_to_parse() {
        props.setMemoryThreshold(0.0);
        InputStream in = utf8("anything");

        assertThatThrownBy(() -> service.parse(in, "any.txt", "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds threshold");
    }

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
