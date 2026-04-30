package com.zhituagent.orchestrator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU counter that flags repeated identical (toolName, argsHash) calls
 * as a loop. The orchestrator turns this into an observation fed back to the
 * LLM ("tool call loop detected, please change arguments or pick a different
 * tool"), preventing infinite retry death-spirals during ReAct loops.
 *
 * <p>Process-wide for now; will move to per-conversation scope when SG lands.
 */
final class LoopDetector {

    private static final int MAX_KEYS = 256;
    private static final int LOOP_THRESHOLD = 3;

    private final Map<String, Integer> counts = new LinkedHashMap<>(MAX_KEYS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > MAX_KEYS;
        }
    };

    synchronized int record(String toolName, String argumentsJson) {
        String key = toolName + "#" + sha256(argumentsJson == null ? "" : argumentsJson);
        int next = counts.getOrDefault(key, 0) + 1;
        counts.put(key, next);
        return next;
    }

    static int loopThreshold() {
        return LOOP_THRESHOLD;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
