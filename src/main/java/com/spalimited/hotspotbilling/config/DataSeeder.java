package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Technician;
import com.spalimited.hotspotbilling.repository.PlanRepository;
import com.spalimited.hotspotbilling.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Optionally seeds a few starter plans and a first technician account, for
 * local development convenience. OFF by default so a real (self-service)
 * account starts empty — the ISP creates their own plans, staff and routers.
 * Enable for a dev box with {@code app.seed-starter-data=true} (SEED_STARTER_DATA).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final PlanRepository planRepository;
    private final TechnicianRepository technicianRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${tech.username}")
    private String techUsername;

    @Value("${tech.password}")
    private String techPassword;

    @Value("${app.seed-starter-data:false}")
    private boolean seedStarterData;

    @Override
    public void run(String... args) {
        if (!seedStarterData) {
            return; // real accounts start clean
        }
        if (technicianRepository.count() == 0) {
            technicianRepository.save(Technician.builder()
                    .username(techUsername)
                    .passwordHash(passwordEncoder.encode(techPassword))
                    .fullName("Field Technician")
                    .build());
            log.info("Seeded default technician account '{}'", techUsername);
        }
        if (planRepository.count() > 0) {
            return;
        }
        planRepository.saveAll(List.of(
                Plan.builder().name("1 Hour").price(new BigDecimal("20"))
                        .durationMinutes(60).bandwidth("3M/3M").build(),
                Plan.builder().name("6 Hours").price(new BigDecimal("50"))
                        .durationMinutes(360).bandwidth("5M/5M").build(),
                Plan.builder().name("24 Hours").price(new BigDecimal("100"))
                        .durationMinutes(1440).bandwidth("5M/5M").build(),
                Plan.builder().name("Weekly").price(new BigDecimal("500"))
                        .durationMinutes(10080).bandwidth("10M/10M").build()));
        log.info("Seeded default plans");
    }
}
