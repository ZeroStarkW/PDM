package com.pdm.mvp.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) { return Map.of("token", csrfToken.getToken()); }

    @PostMapping("/login")
    public UserSummary login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (org.springframework.security.core.AuthenticationException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, servletRequest, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, servletRequest, response);
        return UserSummary.from((UserEntity) authentication.getPrincipal());
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        new SecurityContextLogoutHandler().logout(request, response, auth);
        return Map.of("success", true);
    }

    @GetMapping("/me")
    public UserSummary me(Authentication authentication) {
        return UserSummary.from((UserEntity) authentication.getPrincipal());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record UserSummary(Long id, String username, java.util.Set<String> roles) {
        static UserSummary from(UserEntity user) { return new UserSummary(user.getId(), user.getUsername(), user.getRoles()); }
    }
}
