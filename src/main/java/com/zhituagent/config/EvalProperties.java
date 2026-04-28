package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "zhitu.eval")
public class EvalProperties {

    private boolean enabled = false;
    private boolean exitAfterRun = false;
    private String fixtureResource = "eval/baseline-chat-cases.jsonl";
    private String reportDir = "target/eval-reports";
    private List<String> modes = new ArrayList<>(List.of("dense", "dense-rerank", "hybrid-rerank"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isExitAfterRun() {
        return exitAfterRun;
    }

    public void setExitAfterRun(boolean exitAfterRun) {
        this.exitAfterRun = exitAfterRun;
    }

    public String getFixtureResource() {
        return fixtureResource;
    }

    public void setFixtureResource(String fixtureResource) {
        this.fixtureResource = fixtureResource;
    }

    public String getReportDir() {
        return reportDir;
    }

    public void setReportDir(String reportDir) {
        this.reportDir = reportDir;
    }

    public List<String> getModes() {
        return modes;
    }

    public void setModes(List<String> modes) {
        this.modes = modes == null ? new ArrayList<>() : new ArrayList<>(modes);
    }
}
