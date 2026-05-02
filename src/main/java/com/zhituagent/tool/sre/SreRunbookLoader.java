package com.zhituagent.tool.sre;

import com.zhituagent.rag.KnowledgeIngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SreRunbookLoader implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SreRunbookLoader.class);

    private final KnowledgeIngestService ingestService;
    private final SreFixtureLoader fixtureLoader;
    private final boolean enabled;

    public SreRunbookLoader(KnowledgeIngestService ingestService,
                            SreFixtureLoader fixtureLoader,
                            @Value("${zhitu.app.multi-agent-enabled:false}") boolean enabled) {
        this.ingestService = ingestService;
        this.fixtureLoader = fixtureLoader;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            LOG.info("SRE runbook autoload skipped (multi-agent-enabled=false)");
            return;
        }
        Map<String, String> runbooks = fixtureLoader.loadAllRunbooks();
        if (runbooks.isEmpty()) {
            LOG.warn("Multi-agent enabled but no runbook fixtures found at sre-fixtures/runbooks/*.md");
            return;
        }
        for (Map.Entry<String, String> entry : runbooks.entrySet()) {
            String alertName = entry.getKey();
            String content = entry.getValue();
            String question = "How to handle " + alertName + " alert? What is the runbook?";
            String sourceName = "runbook-" + alertName;
            ingestService.ingest(question, content, sourceName);
        }
        LOG.info("SRE runbook autoload completed: {} runbooks ingested ({})",
                runbooks.size(), runbooks.keySet());
    }
}
