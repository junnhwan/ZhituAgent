package com.zhituagent.api;

import com.zhituagent.api.dto.SessionCreateRequest;
import com.zhituagent.api.dto.SessionDetailResponse;
import com.zhituagent.api.dto.SessionResponse;
import com.zhituagent.session.SessionService;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public SessionResponse createSession(@Valid @RequestBody SessionCreateRequest request) {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return sessionService.createSession(userId, request.title());
    }

    @GetMapping("/{sessionId}")
    public SessionDetailResponse getSession(@PathVariable String sessionId) {
        return sessionService.getSession(sessionId);
    }
}
