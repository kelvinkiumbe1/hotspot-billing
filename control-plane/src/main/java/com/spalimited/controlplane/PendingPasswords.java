package com.spalimited.controlplane;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The password somebody chose at signup, held until they click the link in
 * their email.
 *
 * <p>Verification splits signup into two moments and the password arrives at the
 * first one, while provisioning — which needs it, to seed the owner login —
 * happens at the second. Something has to bridge the gap.
 *
 * <p><strong>In memory, on purpose, and this is a trade rather than an
 * oversight.</strong> The alternative is a plaintext password in the registry
 * database for however long the person takes to check their email, which is a
 * far worse place for it: backed up, replicated, and readable by anything with
 * a database connection. Here it lives in one process and dies with it.
 *
 * <p>What that costs: a control-plane restart loses the pending passwords. The
 * signups themselves survive — they are rows — and verifying afterwards still
 * works, it just provisions without a chosen password, and the provisioning
 * script generates one exactly as it already does for an admin-triggered retry.
 * Nobody is stranded; at worst somebody gets a generated password instead of
 * the one they typed.
 *
 * <p>Bounded and expiring, because an unbounded map fed by a public endpoint is
 * a way to exhaust memory rather than a cache.
 */
@Component
@Slf4j
public class PendingPasswords {

    /** As long as a verification link is good for. Nothing outlives its link. */
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * A ceiling well above any real signup rate and well below anything that
     * matters for memory. Once reached, the oldest entries go — losing a pending
     * password only downgrades that signup to a generated one.
     */
    private static final int MAX = 500;

    private record Held(String password, Instant expiresAt) {
    }

    private final Map<Long, Held> held = new ConcurrentHashMap<>();

    public void put(Long tenantId, String password) {
        if (tenantId == null || password == null || password.isBlank()) {
            return;
        }
        sweep();
        if (held.size() >= MAX) {
            // Drop the closest to expiry rather than refusing the new signup.
            held.entrySet().stream()
                    .min(Map.Entry.comparingByValue(
                            java.util.Comparator.comparing(Held::expiresAt)))
                    .map(Map.Entry::getKey)
                    .ifPresent(held::remove);
            log.warn("Pending-password store is full ({}); dropped the oldest entry", MAX);
        }
        held.put(tenantId, new Held(password, Instant.now().plus(TTL)));
    }

    /**
     * Takes the password out, if it is still there.
     *
     * <p>Removed on read: it is needed once, and leaving it behind after
     * provisioning would keep a secret alive for no reason.
     */
    public Optional<String> take(Long tenantId) {
        sweep();
        Held entry = held.remove(tenantId);
        return entry == null ? Optional.empty() : Optional.of(entry.password());
    }

    public void forget(Long tenantId) {
        held.remove(tenantId);
    }

    private void sweep() {
        Instant now = Instant.now();
        Iterator<Map.Entry<Long, Held>> it = held.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt().isBefore(now)) {
                it.remove();
            }
        }
    }
}
