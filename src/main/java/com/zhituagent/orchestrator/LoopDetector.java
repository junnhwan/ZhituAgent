package com.zhituagent.orchestrator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

// 循环检测器：用 LRU 计数器追踪 (toolName, argsHash) 的调用次数，
// 相同参数的工具调用 ≥3 次判定为循环，向 LLM 返回 "loop detected" 观察信号迫使其换策略。
// argsHash 用 SHA-256 防碰撞，LRU 上限 256 key 自动淘汰旧条目。
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
