package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Office logins live in the database as StaffUser rows, each with a role;
 * field logins are Technician rows. Every office account also carries the
 * ADMIN role so the existing /api/admin/** paths stay reachable, with the
 * finer-grained decisions made per endpoint by @PreAuthorize against the
 * permission authorities (see StaffUser.permissions).
 *
 * <p>The account in application.properties survives as a break-glass login.
 * It seeds the first Owner, and it keeps working afterwards so a mistake in
 * the staff table cannot lock the owner out of their own system. Every use
 * of it is logged loudly.
 *
 * <p>CSRF is off because the API is stateless JSON and the M-Pesa callback
 * cannot carry a CSRF token.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter)
            throws Exception {
        http
                // A session token is checked before Basic auth, so a browser
                // that has signed in never falls back to replaying a password.
                .addFilterBefore(bearerTokenFilter, BasicAuthenticationFilter.class)
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/tech/**").hasAnyRole("ADMIN", "TECHNICIAN")
                        // Enrolling or listing passkeys is done by an already
                        // signed-in person; the /login/** ceremony below is not.
                        .requestMatchers("/api/auth/passkey/register/**").authenticated()
                        .requestMatchers("/api/auth/passkey/credentials/**").authenticated()
                        .anyRequest().permitAll())
                // Plain 401 JSON without a WWW-Authenticate: Basic header —
                // that header makes browsers open their native login popup
                // instead of letting our login form show the error.
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Wrong username or password\"}");
                }))
                // A signed-in user reaching for something their role does not
                // cover gets a 403 with a readable reason, not an empty body.
                .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, denied) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write(
                            "{\"message\":\"Your role does not allow that. Ask an owner if you need access.\"}");
                }));
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${admin.username}") String adminUsername,
            @Value("${admin.password}") String adminPassword,
            StaffUserRepository staff,
            TechnicianRepository technicians,
            PasswordEncoder encoder) {
        String encodedBreakGlass = encoder.encode(adminPassword);

        return username -> {
            StaffUser member = staff.findByUsernameAndActiveTrue(username).orElse(null);
            if (member != null) {
                if (member.isLocked()) {
                    throw new UsernameNotFoundException("Account locked: " + username);
                }
                // Without this the second factor could be skipped entirely by
                // sending a password on every request instead of signing in.
                if (member.isTotpEnabled()) {
                    throw new UsernameNotFoundException("Two-factor account: " + username);
                }
                return User.withUsername(member.getUsername())
                        .password(member.getPasswordHash())
                        .authorities(authoritiesFor(member.getRole()))
                        .build();
            }

            // Break-glass: only while it is not shadowed by a real staff row,
            // so disabling that row cannot silently re-open this door.
            if (username.equals(adminUsername) && staff.findByUsername(username).isEmpty()) {
                log.warn("Break-glass login used for '{}' — create a named Owner account under "
                        + "Organisation → Staff so the audit log can attribute actions", adminUsername);
                return User.withUsername(adminUsername)
                        .password(encodedBreakGlass)
                        .authorities(authoritiesFor(StaffUser.Role.OWNER))
                        .build();
            }

            Technician tech = technicians.findByUsernameAndActiveTrue(username)
                    .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
            return User.withUsername(tech.getUsername())
                    .password(tech.getPasswordHash())
                    .roles("TECHNICIAN")
                    .build();
        };
    }

    /** ROLE_ADMIN for the path rules, ROLE_&lt;role&gt; plus the permissions for @PreAuthorize. */
    private static String[] authoritiesFor(StaffUser.Role role) {
        List<String> authorities = new ArrayList<>();
        authorities.add("ROLE_ADMIN");
        authorities.add("ROLE_" + role.name());
        authorities.addAll(StaffUser.permissions(role));
        return authorities.toArray(String[]::new);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Lets the Vite dev server call the API. A pattern rather than a fixed
     * port because Vite hops to 5174/5175 when 5173 is taken, and a mismatch
     * shows up as a 403 "Invalid CORS request" on the login POST only (GETs
     * are same-origin through the proxy and carry no Origin header, so they
     * slip past — which hides the problem until the first POST). In production
     * the built frontend is served same-origin, so none of this applies.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
