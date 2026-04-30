package com.zhituagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zhitu.rag")
public class RagProperties {

    private boolean hybridEnabled = true;
    private int lexicalTopK = 10;
    private double minAcceptedScore = 0.15;
    private boolean contextualEnabled = false;
    private String fusionStrategy = "linear";
    private boolean selfRagEnabled = false;
    private int selfRagMaxRewrites = 2;

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public void setHybridEnabled(boolean hybridEnabled) {
        this.hybridEnabled = hybridEnabled;
    }

    public int getLexicalTopK() {
        return lexicalTopK;
    }

    public void setLexicalTopK(int lexicalTopK) {
        this.lexicalTopK = lexicalTopK;
    }

    public double getMinAcceptedScore() {
        return minAcceptedScore;
    }

    public void setMinAcceptedScore(double minAcceptedScore) {
        this.minAcceptedScore = minAcceptedScore;
    }

    public boolean isContextualEnabled() {
        return contextualEnabled;
    }

    public void setContextualEnabled(boolean contextualEnabled) {
        this.contextualEnabled = contextualEnabled;
    }

    public String getFusionStrategy() {
        return fusionStrategy;
    }

    public void setFusionStrategy(String fusionStrategy) {
        this.fusionStrategy = fusionStrategy;
    }

    public boolean isSelfRagEnabled() {
        return selfRagEnabled;
    }

    public void setSelfRagEnabled(boolean selfRagEnabled) {
        this.selfRagEnabled = selfRagEnabled;
    }

    public int getSelfRagMaxRewrites() {
        return selfRagMaxRewrites;
    }

    public void setSelfRagMaxRewrites(int selfRagMaxRewrites) {
        this.selfRagMaxRewrites = selfRagMaxRewrites;
    }
}
