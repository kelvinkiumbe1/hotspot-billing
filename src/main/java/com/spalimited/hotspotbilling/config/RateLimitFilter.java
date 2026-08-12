package com.spalimited.hotspotbilling.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounds how often one caller can hit the things worth abusing.
 *
 * <p>Password guessing is handled by counting rejections rather than
 * requests. The API is stateless Basic auth, so there is no sign-in
 * endpoint to watch — every request carries credentials and a wrong
 * password is simply a 401. Counting 401s targets the attacker directly
 * and leaves someone working normally completely alone, however busy they
 * are. Counting requests instead would throttle a legitimate operator on a
 * heavy page while barely inconveniencing a script.
 *
 * <p>Volume limits are separate and loose: enough to stop a runaway client
 * or a scraper, high enough that nobody using the product ever meets them.
 *
 * <p>Deliberately in memory. One deployment serves one ISP, so there is no
 * cluster to share state with, and Redis for this would be more moving
 * parts than the problem justifies. A restart forgets its counters, which
 * is a fair trade for something whose job is to slow an attacker down
 * rather than to be an audit record.
 */
@Component
// Must sit in front of Spring Security, whose chain runs at -100 and answers a
// bad password itself, so a filter ordered after it never sees the 401 it is
// supposed to be counting.
@Order(-110)
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /** Wrong passwords tolerated from one address before it is shut out. */
    private static final int FAILED_AUTH_LIMIT = 10;
    private static final Duration FAILED_AUTH_WINDOW = Duration.ofMinutes(10);

    /** Public portal: voucher redemption, tickets, trials, self-service pay. */
    private static final int PUBLIC_LIMIT = 40;
    private static final Duration PUBLIC_WINDOW = Duration.ofMinutes(1);

    /** Signed-in staff and technicians. Only a script reaches this. */
    private static final int STAFF_LIMIT = 600;
    private static final Duration STAFF_WINDOW = Duration.ofMinutes(1);

    private final Map<String, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    private volatile Instant lastSweep = Instant.now();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Safaricom's callbacks are never throttled. The confirmation is sent
        // once; dropping it means the customer has paid and we never find out.
        if (path.startsWith("/api/payments/mpesa/")) {
            chain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);

        // Never throttle loopback. In production the reverse proxy forwards the
        // real client IP, so this only matches genuine local traffic — dev,
        // testing and server-local health checks — which shouldn't be limited.
        if (isLoopback(ip)) {
            chain.doFilter(request, response);
            return;
        }

        sweepOccasionally();

        boolean staff = path.startsWith("/api/admin/") || path.startsWith("/api/tech/");

        // Too many rejected credentials from this address: stop answering the
        // signed-in areas, so guessing costs time rather than nothing.
        //
        // Scoped to those areas on purpose. Blocking the address outright
        // would take the captive portal down for every customer sharing it
        // the moment an operator mistyped their own password — the office and
        // the hotspot are usually behind the same address.
        if (staff && count(failures, ip, FAILED_AUTH_WINDOW) >= FAILED_AUTH_LIMIT) {
            log.warn("Blocking {} — {} rejected sign-ins within {} minutes",
                    ip, FAILED_AUTH_LIMIT, FAILED_AUTH_WINDOW.toMinutes());
            reject(response, FAILED_AUTH_WINDOW,
                    "Too many failed sign-in attempts. Wait "
                            + FAILED_AUTH_WINDOW.toMinutes() + " minutes and try again.");
            return;
        }

        int limit = staff ? STAFF_LIMIT : PUBLIC_LIMIT;
        Duration window = staff ? STAFF_WINDOW : PUBLIC_WINDOW;

        if (!record(requests, (staff ? "s|" : "p|") + ip, limit, window)) {
            reject(response, window, "Too many requests. Slow down and try again shortly.");
            return;
        }

        chain.doFilter(request, response);

        // Record the rejection after the fact, so the count reflects actual
        // authentication failures rather than guesses about which paths
        // needed credentials.
        if (response.getStatus() == 401) {
            record(failures, ip, Integer.MAX_VALUE, FAILED_AUTH_WINDOW);
        }
    }

    private void reject(HttpServletResponse response, Duration window, String message)
            throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.setHeader("Retry-After", String.valueOf(window.toSeconds()));
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }

    /** Adds a hit, returning false when the window is already full. */
    private boolean record(Map<String, Deque<Instant>> store, String key, int limit, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> hits = store.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst().isBefore(cutoff)) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }

    private int count(Map<String, Deque<Instant>> store, String key, Duration window) {
        Deque<Instant> hits = store.get(key);
        if (hits == null) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(window);
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst().isBefore(cutoff)) {
                hits.pollFirst();
            }
            return hits.size();
        }
    }

    /** Drops callers nobody has seen lately, so the maps do not grow forever. */
    private void sweepOccasionally() {
        if (Instant.now().isBefore(lastSweep.plus(Duration.ofMinutes(10)))) {
            return;
        }
        lastSweep = Instant.now();
        Instant stale = Instant.now().minus(Duration.ofMinutes(20));
        for (Map<String, Deque<Instant>> store : java.util.List.of(requests, failures)) {
            store.entrySet().removeIf(e -> {
                Deque<Instant> hits = e.getValue();
                synchronized (hits) {
                    return hits.isEmpty() || hits.peekLast().isBefore(stale);
                }
            });
        }
    }

    /**
     * Behind the reverse proxy the socket address is the proxy, so the
     * forwarded header wins — but only its first entry, since anything after
     * that can be set by the client.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean isLoopback(String ip) {
        if (ip == null) return false;
        return ip.equals("127.0.0.1") || ip.equals("::1")
                || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("127.");
    }
}
