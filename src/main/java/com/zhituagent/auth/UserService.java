package com.zhituagent.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();

    public User create(String tenantId, String email, String password) {
        String key = tenantId + ":" + email.toLowerCase();
        if (usersByEmail.containsKey(key)) {
            throw new IllegalArgumentException("Email already registered");
        }

        User user = new User(tenantId, email.toLowerCase(), passwordEncoder.encode(password));
        usersByEmail.put(key, user);
        return user;
    }

    public Optional<User> findByEmail(String tenantId, String email) {
        String key = tenantId + ":" + email.toLowerCase();
        return Optional.ofNullable(usersByEmail.get(key));
    }

    public Optional<User> authenticate(String tenantId, String email, String password) {
        return findByEmail(tenantId, email)
                .filter(user -> passwordEncoder.matches(password, user.getPasswordHash()));
    }
}
