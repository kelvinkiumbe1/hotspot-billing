package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.service.ApiTokenService;
import com.spalimited.hotspotbilling.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts a session token in place of a username and password.
 *
 * <p>Runs alongside Basic auth rather than replacing it: the technician app
 * and any scripts keep working unchanged, while anyone with two-factor
 * enabled signs in properly and carries a token. An account with 2FA on
 * cannot authenticate with Basic at all — see SecurityConfig — or the
 * second factor would be trivially bypassed.
 */
@Component
@RequiredArgsConstructor
public class BearerTokenFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final ApiTokenService apiTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7).trim();
            // A session token first; failing that, a long-lived API token.
            authService.resolve(token)
                    .or(() -> apiTokenService.resolve(token))
                    .ifPresent(user -> authenticate(user, request));
        }
        chain.doFilter(request, response);
    }

    private void authenticate(StaffUser user, HttpServletRequest request) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        user.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        // Marks a read-only evaluation session; DemoReadOnlyFilter blocks its writes.
        if (user.isDemo()) {
            authorities.add(new SimpleGrantedAuthority("DEMO"));
        }

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
        auth.setDetails(request.getRequestURI());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
