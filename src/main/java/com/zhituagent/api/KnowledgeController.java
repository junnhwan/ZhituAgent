package com.zhituagent.api;

import com.zhituagent.api.dto.KnowledgeWriteRequest;
import com.zhituagent.rag.KnowledgeIngestService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeIngestService knowledgeIngestService;

    public KnowledgeController(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    @PostMapping("/knowledge")
    public Map<String, Object> writeKnowledge(@Valid @RequestBody KnowledgeWriteRequest request) {
        knowledgeIngestService.ingest(request.question(), request.answer(), request.sourceName());
        return Map.of(
                "success", true,
                "sourceName", request.sourceName(),
                "message", "knowledge written and indexed"
        );
    }
}
