package com.pdm.mvp.security;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Entity
@Table(name = "pdm_user")
public class UserEntity implements UserDetails {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 80)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled = true;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pdm_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", nullable = false, length = 16)
    private Set<String> roles = new HashSet<>();
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UserEntity() {}
    public UserEntity(String username, String passwordHash, Set<String> roles) {
        this.username = username; this.passwordHash = passwordHash; this.roles = new HashSet<>(roles);
    }
    public Long getId() { return id; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return passwordHash; }
    public Set<String> getRoles() { return Set.copyOf(roles); }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return enabled; }
}
