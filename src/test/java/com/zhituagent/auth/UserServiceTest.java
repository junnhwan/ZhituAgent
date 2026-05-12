package com.zhituagent.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {

    private UserService userService;

    @BeforeEach
    void setup() {
        userService = new UserService(); // In-memory for tests
    }

    @Test
    void shouldCreateUser() {
        User user = userService.create("tenant-1", "test@example.com", "password123");

        assertNotNull(user.getId());
        assertEquals("tenant-1", user.getTenantId());
        assertEquals("test@example.com", user.getEmail());
        assertNotEquals("password123", user.getPasswordHash()); // Should be hashed
    }

    @Test
    void shouldFindByEmail() {
        userService.create("tenant-1", "test@example.com", "password123");

        Optional<User> found = userService.findByEmail("tenant-1", "test@example.com");

        assertTrue(found.isPresent());
        assertEquals("test@example.com", found.get().getEmail());
    }

    @Test
    void shouldNotFindUserFromDifferentTenant() {
        userService.create("tenant-1", "test@example.com", "password123");

        Optional<User> found = userService.findByEmail("tenant-2", "test@example.com");

        assertFalse(found.isPresent());
    }

    @Test
    void shouldAuthenticateWithCorrectPassword() {
        userService.create("tenant-1", "test@example.com", "password123");

        Optional<User> auth = userService.authenticate("tenant-1", "test@example.com", "password123");

        assertTrue(auth.isPresent());
    }

    @Test
    void shouldRejectWrongPassword() {
        userService.create("tenant-1", "test@example.com", "password123");

        Optional<User> auth = userService.authenticate("tenant-1", "test@example.com", "wrong");

        assertFalse(auth.isPresent());
    }

    @Test
    void shouldDetectDuplicateEmail() {
        userService.create("tenant-1", "test@example.com", "password123");

        assertThrows(IllegalArgumentException.class, () -> {
            userService.create("tenant-1", "test@example.com", "another");
        });
    }
}
