package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One customer as the old system described them, with our verdict on whether
 * they can be brought across.
 *
 * <p>Kept after promotion rather than deleted. When a customer rings up three
 * months later about a balance that looks wrong, the answer is in the row they
 * arrived on.
 */
@Entity
@Table(name = "migration_rows")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationRow {

    /**
     * What can be done with this row.
     *
     * <p>COLLISION is not an error: re-uploading the same export must recognise
     * what it already brought across rather than duplicating the book.
     */
    public enum Verdict { NEW, COLLISION, INCOMPLETE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "external_id", length = 120)
    private String externalId;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "phone_number", length = 64)
    private String phoneNumber;

    @Column(name = "pppoe_username", length = 120)
    private String pppoeUsername;

    @Column(name = "pppoe_password", length = 120)
    private String pppoePassword;

    @Column(name = "plan_name", length = 200)
    private String planName;

    @Column(name = "monthly_price", precision = 12, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "static_ip", length = 64)
    private String staticIp;

    @Column(name = "external_status", length = 64)
    private String externalStatus;

    @Column(precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "paid_until")
    private Instant paidUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Verdict verdict;

    @Column(name = "verdict_note", length = 500)
    private String verdictNote;

    @Column(name = "matched_plan_id")
    private Long matchedPlanId;

    @Column(name = "subscriber_id")
    private Long subscriberId;

    @Column(length = 4000)
    private String raw;
}
