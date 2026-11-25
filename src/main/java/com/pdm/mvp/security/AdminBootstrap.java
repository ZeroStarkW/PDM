package com.pdm.mvp.security;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminBootstrap {
    @Bean
    ApplicationRunner bootstrapAdmin(UserRepository users, PasswordEncoder encoder,
            @Value("${pdm.bootstrap.admin-username:}") String username,
            @Value("${pdm.bootstrap.admin-password:}") String password) {
        return args -> {
            if (username.isBlank() != password.isBlank()) {
                throw new IllegalStateException("PDM_ADMIN_USERNAME and PDM_ADMIN_PASSWORD must both be set");
            }
            if (!username.isBlank() && !users.existsAdmin() && !users.existsByUsername(username)) {
                users.save(new UserEntity(username, encoder.encode(password), Set.of("ADMIN")));
            }
        };
    }
}
