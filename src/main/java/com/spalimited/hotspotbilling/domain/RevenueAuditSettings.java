package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * How the nightly revenue audit runs. Single row (id = 1).
 */
@Entity
@Table(name = "revenue_audit_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevenueAuditSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** Text the operator when the sweep turns up a new high-severity finding. */
    @Builder.Default
    @Column(nullable = false)
    private boolean alertOperator = true;

    /** How long a PayBill payment may sit unmatched before it's flagged. */
    @Builder.Default
    @Column(nullable = false)
    private int unmatchedHours = 24;

    /** Days past paid-until before an un-suspended subscriber is flagged. */
    @Builder.Default
    @Column(nullable = false)
    private int lapsedGraceDays = 1;

    /** How far back the payment and voucher checks look. */
    @Builder.Default
    @Column(nullable = false)
    private int lookbackDays = 60;

    /**
     * Router accounts that are legitimately not sold through the system —
     * a test login, a staff device, MikroTik's own default-trial user.
     * Comma-separated names, matched case-insensitively.
     */
    @Column(length = 2000)
    private String ignoredAccounts;

    private Instant lastRunAt;
}
