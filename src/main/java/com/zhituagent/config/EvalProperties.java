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
    private String label = "";
    private List<String> compareLabels = new ArrayList<>();
    private final Cmteb cmteb = new Cmteb();

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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null ? "" : label;
    }

    public List<String> getCompareLabels() {
        return compareLabels;
    }

    public void setCompareLabels(List<String> compareLabels) {
        this.compareLabels = compareLabels == null ? new ArrayList<>() : new ArrayList<>(compareLabels);
    }

    public Cmteb getCmteb() {
        return cmteb;
    }

    public static class Cmteb {

        private boolean enabled = false;
        private String fixtureDir = "target/eval-fixtures/cmteb-t2";
        private int chunkSize = 512;
        private int chunkOverlap = 64;
        private int topK = 10;
        private int recallK = 5;
        private String retrievalMode = "hybrid";
        private String label = "v1";
        private boolean skipIngest = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFixtureDir() {
            return fixtureDir;
        }

        public void setFixtureDir(String fixtureDir) {
            this.fixtureDir = fixtureDir;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public int getRecallK() {
            return recallK;
        }

        public void setRecallK(int recallK) {
            this.recallK = recallK;
        }

        public String getRetrievalMode() {
            return retrievalMode;
        }

        public void setRetrievalMode(String retrievalMode) {
            this.retrievalMode = retrievalMode;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label == null ? "v1" : label;
        }

        public boolean isSkipIngest() {
            return skipIngest;
        }

        public void setSkipIngest(boolean skipIngest) {
            this.skipIngest = skipIngest;
        }
    }
}
