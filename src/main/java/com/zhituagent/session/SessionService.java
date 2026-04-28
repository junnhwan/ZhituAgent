package com.zhituagent.session;

import com.zhituagent.api.dto.ChatMessageView;
import com.zhituagent.api.dto.SessionDetailResponse;
import com.zhituagent.api.dto.SessionResponse;
import com.zhituagent.common.error.ApiException;
import com.zhituagent.common.error.ErrorCode;
import com.zhituagent.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import com.zhituagent.memory.MemorySnapshot;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final MemoryService memoryService;

    @Autowired
    public SessionService(SessionRepository sessionRepository, MemoryService memoryService) {
        this.sessionRepository = sessionRepository;
        this.memoryService = memoryService;
    }

    public SessionService(MemoryService memoryService) {
        this(new InMemorySessionRepository(), memoryService);
    }

    public SessionResponse createSession(String userId, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String resolvedTitle = (title == null || title.isBlank()) ? "新会话" : title;
        SessionMetadata metadata = new SessionMetadata(sessionId, userId, resolvedTitle, now, now);
        sessionRepository.save(metadata);
        return metadata.toResponse();
    }

    public SessionResponse ensureSession(String sessionId, String userId) {
        SessionMetadata metadata = sessionRepository.findById(sessionId).orElseGet(() -> {
            OffsetDateTime now = OffsetDateTime.now();
            return new SessionMetadata(sessionId, userId, "会话 " + sessionId, now, now);
        });
        metadata.touch();
        sessionRepository.save(metadata);
        return metadata.toResponse();
    }

    public SessionDetailResponse getSession(String sessionId) {
        SessionMetadata metadata = sessionRepository.findById(sessionId).orElse(null);
        if (metadata == null) {
            throw new ApiException(ErrorCode.SESSION_NOT_FOUND, "session not found: " + sessionId);
        }
        metadata.touch();
        sessionRepository.save(metadata);
        MemorySnapshot snapshot = memoryService.snapshot(sessionId);
        return new SessionDetailResponse(
                metadata.toResponse(),
                snapshot.summary(),
                snapshot.recentMessages().stream()
                        .map(message -> new ChatMessageView(message.role(), message.content(), message.timestamp()))
                        .toList(),
                snapshot.facts()
        );
    }

    public void appendMessage(String sessionId, String userId, String role, String content) {
        SessionMetadata metadata = sessionRepository.findById(sessionId).orElseGet(() -> {
            OffsetDateTime now = OffsetDateTime.now();
            return new SessionMetadata(sessionId, userId, "会话 " + sessionId, now, now);
        });
        if ((metadata.getTitle() == null || metadata.getTitle().startsWith("会话 ")) && "user".equals(role) && content != null && !content.isBlank()) {
            metadata.setTitle(content.length() > 18 ? content.substring(0, 18) + "..." : content);
        }
        memoryService.append(sessionId, role, content);
        metadata.touch();
        sessionRepository.save(metadata);
    }
}
