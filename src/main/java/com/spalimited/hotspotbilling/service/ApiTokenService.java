package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.ApiToken;
import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.ApiTokenRepository;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Personal access tokens for the REST API. Unlike a session token these do
 * not expire; they live until revoked. Stored as-is (like session tokens),
 * shown in full only once at creation and masked everywhere after.
 */
@Service
@RequiredArgsConstructor
public class ApiTokenService {

    private final ApiTokenRepository tokens;
    private final StaffUserRepository staff;
    private final SecureRandom random = new SecureRandom();

    public record Created(Long id, String name, String token, Instant createdAt) {
    }

    @Transactional
    public Created create(String name, StaffUser owner) {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ApiToken saved = tokens.save(ApiToken.builder()
                .token(raw)
                .name(name == null || name.isBlank() ? "API token" : name.trim())
                .staffUserId(owner.getId())
                .createdBy(owner.getUsername())
                .build());
        return new Created(saved.getId(), saved.getName(), raw, saved.getCreatedAt());
    }

    /** Masked listing — never returns the raw token again. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return tokens.findAllByOrderByCreatedAtDesc().stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "name", t.getName(),
                        "masked", t.getToken().substring(0, 6) + "…" + t.getToken().substring(t.getToken().length() - 4),
                        "createdBy", t.getCreatedBy() == null ? "" : t.getCreatedBy(),
                        "createdAt", t.getCreatedAt(),
                        "lastUsedAt", t.getLastUsedAt() == null ? "" : t.getLastUsedAt()))
                .toList();
    }

    @Transactional
    public void revoke(Long id) {
        tokens.deleteById(id);
    }

    /**
     * The staff member behind a token, if it is valid and their account is
     * still good. Touches last-used at most once a minute to avoid a write
     * on every API call.
     */
    @Transactional
    public Optional<StaffUser> resolve(String token) {
        return tokens.findByToken(token)
                .flatMap(t -> {
                    if (t.getLastUsedAt() == null
                            || t.getLastUsedAt().isBefore(Instant.now().minusSeconds(60))) {
                        t.setLastUsedAt(Instant.now());
                        tokens.save(t);
                    }
                    return staff.findById(t.getStaffUserId());
                })
                .filter(StaffUser::isActive)
                .filter(u -> !u.isLocked());
    }
}
