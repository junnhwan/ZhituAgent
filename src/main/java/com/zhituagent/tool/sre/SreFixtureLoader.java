package com.zhituagent.tool.sre;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class SreFixtureLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SreFixtureLoader.class);

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resolver;

    public SreFixtureLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.resolver = new PathMatchingResourcePatternResolver();
    }

    public Optional<JsonNode> loadJson(String classpathPath) {
        Resource resource = resolver.getResource("classpath:" + classpathPath);
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(objectMapper.readTree(in));
        } catch (IOException e) {
            LOG.warn("Failed to read fixture {}: {}", classpathPath, e.getMessage());
            return Optional.empty();
        }
    }

    public Map<String, String> loadAllRunbooks() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Resource[] resources = resolver.getResources("classpath:sre-fixtures/runbooks/*.md");
            for (Resource r : resources) {
                String name = r.getFilename();
                if (name == null || !name.endsWith(".md")) {
                    continue;
                }
                String alertName = name.substring(0, name.length() - 3);
                try (InputStream in = r.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    result.put(alertName, content);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan runbooks: {}", e.getMessage());
        }
        return result;
    }
}
