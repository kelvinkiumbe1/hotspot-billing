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
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.core.annotation.Order;
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
import java.util.Arrays;
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

    /**
     * Files the browser fetches before anybody has signed in.
     *
     * <p>A constant rather than an inline list because one of these is easy to
     * forget and impossible to spot: the web app manifest was public while every
     * icon it names was not, so the install prompt offered to add an app it could
     * not draw and quietly fell back to a generic glyph. It reads as the wrong
     * logo, not as a 401, and it is only visible on the install prompt itself.
     *
     * <p>{@code PublicStaticFilesTest} holds this list against the manifest and
     * against what the frontend actually ships, so adding an icon without opening
     * it up fails the build instead of shipping.
     */
    static final String[] PUBLIC_STATIC = {
            "/", "/index.html", "/favicon.*", "/icons.svg",
            "/manifest.webmanifest", "/robots.txt", "/sw.js",
            "/icon-192.png", "/icon-512.png",
            "/icon-maskable-512.png", "/apple-touch-icon.png",
            "/assets/**", "/api/uploads/**",
    };

    /**
     * The ACS answers to devices, not to people, and needs its own chain.
     *
     * <p>TR-069 carries the device's credentials as HTTP Basic on every request.
     * On the main chain that header is picked up by Spring's Basic filter, looked
     * up in the staff table, not found, and refused — so a device with perfectly
     * good ACS credentials got a 401 before AcsController ever ran. Here Basic is
     * off, the path is open, and {@code AcsAuth} does the checking against the
     * credentials an operator configured for devices.
     */
    @Bean
    @Order(1)
    SecurityFilterChain acsFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/acs")
                .csrf(csrf -> csrf.disable())
                // Both off deliberately: this chain must not try to resolve a
                // device's credentials as a staff login.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http, BearerTokenFilter bearerTokenFilter,
                                    DemoReadOnlyFilter demoReadOnlyFilter)
            throws Exception {
        http
                // A session token is checked before Basic auth, so a browser
                // that has signed in never falls back to replaying a password.
                .addFilterBefore(bearerTokenFilter, BasicAuthenticationFilter.class)
                // After authentication is established, block writes from a demo
                // session so the read-only evaluation login cannot change data.
                .addFilterAfter(demoReadOnlyFilter, BasicAuthenticationFilter.class)
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                // Fail closed. This used to end in permitAll, which meant a new
                // controller was public until somebody remembered to add a
                // matcher -- and several never did, so strangers could read a
                // customer's credit balance and take an advance in their name.
                // Everything public is now named here, and the list is the
                // thing to review when an endpoint is added.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/tech/**").hasAnyRole("ADMIN", "TECHNICIAN")
                        // Enrolling or listing passkeys, and turning 2FA on or
                        // off, are done by an already signed-in person; the
                        // /login/** ceremony below is not. Without the 2FA line
                        // these answered 500 from a null principal instead of 401.
                        .requestMatchers("/api/auth/passkey/register/**").authenticated()
                        .requestMatchers("/api/auth/passkey/credentials/**").authenticated()
                        .requestMatchers("/api/auth/2fa/**").authenticated()

                        // ---- deliberately public from here down ----

                        // The built React app. These are shells: what anyone can
                        // actually do is decided by the API calls behind them.
                        //
                        // The icons are listed one by one rather than as a
                        // wildcard, and they have to be listed at all: the
                        // manifest was public but every icon it names was not,
                        // so the install prompt asked to add an app it could not
                        // draw and fell back to a generic glyph. Nothing about
                        // that says "unauthenticated file missing" when you look
                        // at it -- it just looks like the wrong logo.
                        .requestMatchers(HttpMethod.GET, PUBLIC_STATIC).permitAll()
                        .requestMatchers("/admin", "/tech", "/pay", "/my-account",
                                "/admin/**", "/tech/**", "/pay/**", "/my-account/**",
                                "/error").permitAll()

                        // Signing in, and the passkey ceremony that replaces it.
                        .requestMatchers("/api/auth/login", "/api/auth/logout",
                                "/api/auth/demo", "/api/auth/password-rules",
                                "/api/auth/passkey/login/**").permitAll()

                        // Money arriving. A provider callback cannot carry a
                        // login, so each one proves itself instead -- by
                        // signature, by calling the provider back, or by the
                        // Safaricom IP allowlist. See ProviderWebhookController.
                        .requestMatchers("/api/payments/**", "/api/whatsapp/webhook",
                                "/api/voice/**").permitAll()

                        // What somebody with no account legitimately reaches: the
                        // captive portal, the plan list, their own pass, a support
                        // ticket, and the one-time code that proves a phone is
                        // theirs. A voucher code is itself the bearer credential
                        // for a pass, which is why /api/vouchers is here.
                        .requestMatchers("/api/plans/**", "/api/portal/**",
                                "/api/portal-settings/**", "/api/paybill/**",
                                "/api/promotion/**", "/api/custom-plan/**",
                                "/api/status/**", "/api/tickets/**", "/api/pppoe/**",
                                "/api/vouchers/**", "/api/verify/**",
                                // The country picker on the signup form, and the
                                // one free voucher a new number may claim.
                                "/api/countries", "/api/trial",
                                // The USSD aggregator's callback. Same position
                                // as a payment webhook: a telco cannot sign in.
                                "/api/ussd").permitAll()

                        // A customer has no staff login, so these cannot be
                        // authenticated here -- they prove the caller owns the
                        // phone with a fresh one-time code instead, inside the
                        // controller. See PhoneOwnership.
                        .requestMatchers("/api/credit/**", "/api/loyalty/**",
                                "/api/referral/**").permitAll()

                        .anyRequest().authenticated())
                // Plain 401 JSON without a WWW-Authenticate: Basic header —
                // that header makes browsers open their native login popup
                // instead of letting our login form show the error.
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"message\":\"Wrong username or password\"}");
                }))
                // A signed-in user reaching for something their role does not
                // cover gets a 403 with a readable reason, not an empty body.
                .exceptionHandling(ex -> ex.accessDeniedHandler((request, response, denied) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                            "{\"message\":\"Your role does not allow that. Ask an owner if you need access.\"}");
                }))
                .headers(headers -> headers
                        // Session tokens live in the browser's sessionStorage, so an
                        // injected script is the thing to fear most. script-src 'self'
                        // blocks inline and remote scripts — the core XSS defence. The
                        // other sources are exactly what the UI loads: Google Fonts'
                        // stylesheet + files for the Material Symbols glyphs, and the
                        // OpenStreetMap tiles the fibre map draws. 'unsafe-inline' is
                        // allowed for styles only (React inline style attributes),
                        // never for scripts.
                        .contentSecurityPolicy(csp -> csp.policyDirectives(String.join("; ",
                                "default-src 'self'",
                                "script-src 'self'",
                                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
                                // data: because @fontsource inlines the Fira woff2 as
                                // data URLs; gstatic for the Material Symbols glyphs.
                                "font-src 'self' data: https://fonts.gstatic.com",
                                "img-src 'self' data: blob: https://*.tile.openstreetmap.org",
                                "connect-src 'self'",
                                "frame-ancestors 'none'",
                                "object-src 'none'",
                                "base-uri 'self'",
                                "form-action 'self'")))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(ref -> ref.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // Only sent over HTTPS, so it is a no-op on localhost dev.
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        // Turns off features the app never uses. Deliberately omits
                        // publickey-credentials-*, whose default self-allowance is what
                        // lets passkeys work.
                        .permissionsPolicyHeader(pp -> pp.policy("camera=(), microphone=(), geolocation=(), payment=()")));
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
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors-allowed-origin-patterns:http://localhost:*,http://127.0.0.1:*}") String patternsCsv) {
        CorsConfiguration config = new CorsConfiguration();
        // Dev default is any localhost port (Vite hops around); production sets
        // APP_CORS_ALLOWED_ORIGIN_PATTERNS to its own https origin so a page on
        // some other site cannot drive the API from a signed-in user's browser.
        config.setAllowedOriginPatterns(Arrays.stream(patternsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
