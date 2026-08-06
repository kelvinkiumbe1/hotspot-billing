package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * /api/admin/** requires the ADMIN role, /api/tech/** the TECHNICIAN (or
 * ADMIN) role, both via HTTP Basic; everything else (captive portal
 * endpoints, Daraja callback) stays open. The admin account comes from
 * application.properties; technician accounts live in the database and are
 * managed from the admin Team page. CSRF is off because the API is
 * stateless JSON and the M-Pesa callback cannot carry a CSRF token.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/tech/**").hasAnyRole("ADMIN", "TECHNICIAN")
                        .anyRequest().permitAll())
                // Plain 401 JSON without a WWW-Authenticate: Basic header —
                // that header makes browsers open their native login popup
                // instead of letting our login form show the error.
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Wrong username or password\"}");
                }));
        return http.build();
    }

    @Bean
    UserDetailsService userDetailsService(
            @Value("${admin.username}") String adminUsername,
            @Value("${admin.password}") String adminPassword,
            TechnicianRepository technicians,
            PasswordEncoder encoder) {
        String encodedAdminPassword = encoder.encode(adminPassword);
        return username -> {
            if (username.equals(adminUsername)) {
                return User.withUsername(adminUsername)
                        .password(encodedAdminPassword)
                        .roles("ADMIN")
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

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Allows the Vite dev server to call the API during development. */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
