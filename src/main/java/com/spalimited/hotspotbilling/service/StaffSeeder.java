package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.StaffUser;
import com.spalimited.hotspotbilling.repository.StaffUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Turns the single account in application.properties into a real Owner row
 * on first boot, so the audit log has a name to attribute actions to. Runs
 * once: if any staff account exists, this does nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaffSeeder implements ApplicationRunner {

    private final StaffUserRepository staff;
    private final PasswordEncoder encoder;

    @Value("${admin.username}")
    private String adminUsername;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (staff.count() > 0) {
            return;
        }
        StaffUser owner = staff.save(StaffUser.builder()
                .username(adminUsername.trim().toLowerCase())
                .passwordHash(encoder.encode(adminPassword))
                .fullName("Owner")
                .role(StaffUser.Role.OWNER)
                .seeded(true)
                .createdBy("system")
                .build());
        log.info("Seeded the first office account '{}' as OWNER from application.properties. "
                + "Rename it and change the password under Organisation → Staff.", owner.getUsername());
    }
}
