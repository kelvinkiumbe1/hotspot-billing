package com.spalimited.hotspotbilling.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

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

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
