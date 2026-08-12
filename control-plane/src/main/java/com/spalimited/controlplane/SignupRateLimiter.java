package com.spalimited.controlplane;

import org.springframework.beans.factory.annotation.Value;
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
 *
 * Loopback is exempt so local development/testing isn't throttled, and the
 * limit is configurable via {@code zidi.signup.max-per-hour}.
 */
@Component
public class SignupRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final int maxPerWindow;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public SignupRateLimiter(@Value("${zidi.signup.max-per-hour:5}") int maxPerWindow) {
        this.maxPerWindow = maxPerWindow;
    }

    public synchronized boolean allow(String ip) {
        if (isLoopback(ip)) {
            return true; // don't throttle local dev
        }
        Instant now = Instant.now();
        Deque<Instant> recent = hits.computeIfAbsent(ip == null ? "?" : ip, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
            recent.pollFirst();
        }
        if (recent.size() >= maxPerWindow) {
            return false;
        }
        recent.addLast(now);
        return true;
    }

    private static boolean isLoopback(String ip) {
        if (ip == null) return false;
        return ip.equals("127.0.0.1") || ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1") || ip.equalsIgnoreCase("localhost");
    }
}
