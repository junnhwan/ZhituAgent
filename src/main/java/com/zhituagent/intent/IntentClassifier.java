package com.zhituagent.intent;

import java.util.Map;

/**
 * One layer in the dual-layer intent recognition pipeline. Implementations are
 * expected to be cheap relative to the layer below them — rules first
 * (microseconds), then a cheap LLM (~hundreds of ms), then fall through to the
 * existing expensive path.
 */
public interface IntentClassifier {

    /**
     * Classify a user message. Implementations MUST NOT throw on bad input —
     * return {@link IntentResult#fallthrough(long)} so the caller can transparently
     * skip this layer.
     */
    IntentResult classify(String userMessage, Map<String, Object> sessionMetadata);
}
