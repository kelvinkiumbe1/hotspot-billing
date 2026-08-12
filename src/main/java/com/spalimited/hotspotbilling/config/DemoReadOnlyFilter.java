package com.spalimited.hotspotbilling.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Makes the demo login truly read-only. A demo session carries the DEMO
 * authority (added in {@link BearerTokenFilter}); this rejects any state-
 * changing request it makes to the admin or technician APIs. Safe because
 * every mutation in those APIs is a POST/PUT/PATCH/DELETE and there are no
 * state-changing GETs, so blocking non-GET covers them all — while login,
 * logout and the public M-Pesa callbacks (not under /api/admin) stay open.
 */
@Component
public class DemoReadOnlyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && isDemo(auth) && isMutating(request) && isGuardedPath(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"message\":\"This is a read-only demo — changes are disabled. "
                            + "Create your own account to go live.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isDemo(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "DEMO".equals(a.getAuthority()));
    }

    private static boolean isMutating(HttpServletRequest request) {
        String m = request.getMethod();
        return !("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m));
    }

    private static boolean isGuardedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/api/admin") || uri.startsWith("/api/tech");
    }
}
