package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Rebuilds the status CHECK constraint so a new status can actually be stored.
 *
 * <h2>Why this has to exist</h2>
 *
 * <p>Hibernate writes a CHECK constraint for an enum column naming the values
 * that existed when the table was created. {@code ddl-auto=update} adds new
 * tables and columns but never rebuilds an existing constraint — so adding a
 * value to {@link Tenant.Status} compiles, starts, and then fails at the
 * moment somebody signs up, with a database error nothing in the code hints at.
 *
 * <p>That is not hypothetical. Adding AWAITING_EMAIL did exactly this: the
 * insert was refused by {@code tenants_status_check}, which still named only the
 * original four values.
 *
 * <p>The billing app has the same hazard and handles it in a migration, where
 * the rebuild is explicit and reviewable. The control plane deliberately carries
 * no Flyway — it is a two-table registry — so the rebuild happens here instead,
 * on every start, derived from the enum rather than from a list somebody has to
 * remember to update.
 *
 * <p>Idempotent by construction: drop if present, then add. Running it on a
 * database that is already correct changes nothing.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class SchemaGuard {

    @Bean
    ApplicationRunner rebuildStatusConstraint(JdbcTemplate jdbc) {
        return args -> {
            String values = Arrays.stream(Tenant.Status.values())
                    .map(s -> "'" + s.name() + "'")
                    .collect(Collectors.joining(", "));
            try {
                jdbc.execute("ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_status_check");
                jdbc.execute("ALTER TABLE tenants ADD CONSTRAINT tenants_status_check "
                        + "CHECK (status IN (" + values + "))");
                log.info("Tenant status constraint allows {}", values);
            } catch (Exception e) {
                // Never fatal. A registry that will not start is worse than one
                // whose constraint is a version behind, and the symptom of the
                // latter is a clear database error on the next signup.
                log.warn("Could not rebuild the tenant status constraint: {}", e.getMessage());
            }
        };
    }
}
