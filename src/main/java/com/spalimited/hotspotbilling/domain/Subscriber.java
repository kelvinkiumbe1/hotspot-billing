package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A monthly PPPoE subscriber (home/office customer). Their router dials
 * in with the PPPoE username/password; access lasts until paidUntil and
 * the SubscriptionJob suspends the account when payment lapses.
 */
@Entity
@Table(name = "subscribers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscriber {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String phoneNumber;

    @Column(nullable = false, unique = true)
    private String pppoeUsername;

    @JsonIgnore
    @Column(nullable = false)
    private String pppoePassword;

    /** MikroTik rate limit, e.g. "10M/10M". */
    private String bandwidth;

    /**
     * A fixed address for this customer, allocated from IPAM.
     *
     * <p>Null means "whatever the pool gives them", which is what almost every
     * customer wants. When set, RADIUS hands it out as Framed-IP-Address at
     * login, so the address follows the customer rather than the session.
     */
    @Column(length = 64)
    private String staticIp;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyFee;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private Instant paidUntil;

    /** The paidUntil we last sent an expiry-reminder SMS about. */
    private Instant remindedForExpiry;

    /** The paidUntil we last fired an automatic renewal STK prompt for. */
    private Instant autoStkForExpiry;

    /**
     * Dunning (failed-payment recovery) state. When an auto-renewal isn't paid,
     * {@code dunningCycle} holds the paidUntil we're chasing (so a fresh cycle
     * resets the chase), {@code dunningAttempts} counts retry prompts already
     * sent, and {@code dunningNextAt} is when the next retry is due. All null/0
     * when no recovery is in flight; cleared the moment any payment lands.
     */
    private Instant dunningCycle;

    @Builder.Default
    @Column(nullable = false)
    private int dunningAttempts = 0;

    private Instant dunningNextAt;

    /**
     * Win-back (re-engagement) state, mirroring the dunning fields but running
     * later and slower: after a customer has stayed lapsed, an escalating series
     * of come-back messages goes out. {@code winbackCycle} anchors it to the
     * lapse (so a return-then-relapse resets), {@code winbackStage} counts
     * messages sent, {@code winbackNextAt} is when the next one is due.
     */
    private Instant winbackCycle;

    @Builder.Default
    @Column(nullable = false)
    private int winbackStage = 0;

    private Instant winbackNextAt;

    /** How the most recent successful payment was made (MPESA/CASH). */
    private String lastPaymentMethod;

    private Instant lastPaymentAt;

    /** Username of the admin/technician who signed this customer up. */
    private String createdBy;

    /** Router/site this customer is provisioned on; null means the default. */
    private Long routerId;

    /** Branch/franchise this customer belongs to; null means head office. */
    private Long branchId;

    /**
     * Rolling data usage in MB, refreshed by the monitor job. Nullable so
     * the column can be added to databases that already hold subscribers;
     * read it through {@link #getDataUsedMbOrZero()}.
     */
    private Long dataUsedMb;

    @Transient
    public long getDataUsedMbOrZero() {
        return dataUsedMb != null ? dataUsedMb : 0L;
    }

    private Instant usageResetAt;

    private Instant lastSeenOnlineAt;

    /**
     * Monthly data allowance in MB, or null for uncapped -- which is what every
     * subscriber that existed before this column did, and what most fibre
     * customers should stay on. A cap here is checked against the running total
     * in subscriber_usage_daily, not against dataUsedMb, because that counter is
     * zeroed monthly and rounds every increment down to whole megabytes.
     */
    private Integer dataCapMb;

    /** What to do once the cap is crossed. Null is treated as NOTIFY. */
    @Enumerated(EnumType.STRING)
    private Plan.FupAction fupAction;

    /** RouterOS rate-limit to drop to when throttling, e.g. "2M/2M". */
    private String fupRate;

    private Instant fupAppliedAt;

    /**
     * First day of the cap period the action was applied in.
     *
     * <p>Without this, lifting a throttle at the start of a new month needs a job
     * that runs at midnight on the 1st and is trusted never to miss. With it,
     * "is this throttle stale?" is a comparison any code path can make, so the
     * customer gets their speed back on the next poll after their month turns
     * over whether or not anything fired on time.
     */
    private LocalDate fupCycle;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
