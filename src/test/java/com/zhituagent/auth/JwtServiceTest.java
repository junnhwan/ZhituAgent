package com.zhituagent.auth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    @Test
    void shouldGenerateAndValidateToken() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-that-is-long-enough-32ch!!");
        props.setExpirationMs(3600000);
        props.setIssuer("test");

        JwtService jwtService = new JwtService(props);

        String token = jwtService.generateToken("user-123", "tenant-456", "test@example.com");

        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));

        assertEquals("user-123", jwtService.extractUserId(token));
        assertEquals("tenant-456", jwtService.extractTenantId(token));
        assertEquals("test@example.com", jwtService.extractEmail(token));
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-that-is-long-enough-32ch!!");
        props.setExpirationMs(-1000); // Already expired

        JwtService jwtService = new JwtService(props);

        String token = jwtService.generateToken("user-123", "tenant-456", "test@example.com");

        assertFalse(jwtService.isTokenValid(token));
    }

    @Test
    void shouldRejectInvalidToken() {
        JwtProperties props = new JwtProperties();
        props.setSecret("test-secret-key-that-is-long-enough-32ch!!");

        JwtService jwtService = new JwtService(props);

        assertFalse(jwtService.isTokenValid("invalid.token.here"));
    }

    @Test
    void shouldRejectNullSecret() {
        JwtProperties props = new JwtProperties();
        // secret is null by default
        assertThrows(IllegalArgumentException.class, () -> new JwtService(props));
    }
}
