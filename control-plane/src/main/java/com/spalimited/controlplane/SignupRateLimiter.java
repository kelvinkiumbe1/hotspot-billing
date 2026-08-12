package com.spalimited.controlplane;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a single IP from flooding public signup with junk tenants. A sliding
 * window of a few signups an hour is plenty for a real person and stops a
 * script cold. In-memory is fine — the control plane is a single instance.
 */
@Component
public class SignupRateLimiter {

    private static final int MAX_PER_WINDOW = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public synchronized boolean allow(String ip) {
        Instant now = Instant.now();
        Deque<Instant> recent = hits.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
            recent.pollFirst();
        }
        if (recent.size() >= MAX_PER_WINDOW) {
            return false;
        }
        recent.addLast(now);
        return true;
    }
}
