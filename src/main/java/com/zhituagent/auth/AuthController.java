package com.zhituagent.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            User user = userService.create(request.tenantId(), request.email(), request.password());
            String token = jwtService.generateToken(user.getId(), user.getTenantId(), user.getEmail());
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "user", Map.of(
                            "id", user.getId(),
                            "email", user.getEmail(),
                            "tenantId", user.getTenantId()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return userService.authenticate(request.tenantId(), request.email(), request.password())
                .map(user -> {
                    String token = jwtService.generateToken(user.getId(), user.getTenantId(), user.getEmail());
                    return ResponseEntity.ok(Map.of(
                            "token", token,
                            "user", Map.of(
                                    "id", user.getId(),
                                    "email", user.getEmail(),
                                    "tenantId", user.getTenantId()
                            )
                    ));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }

    public record RegisterRequest(String tenantId, String email, String password) {}
    public record LoginRequest(String tenantId, String email, String password) {}
}
