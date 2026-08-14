package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One thing the revenue audit could not reconcile — money that arrived with
 * no service issued, service running with no money behind it, or an account
 * on the router that the billing system never sold.
 *
 * <p>A finding is identified by its {@link #fingerprint} (kind + subject) so
 * the same problem seen on successive nights stays a single row that ages,
 * rather than a new alert each time.
 */
@Entity
@Table(name = "revenue_findings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevenueFinding {

    public enum Kind {
        /** A successful payment that never produced a voucher. */
        PAID_NO_SERVICE,
        /** One M-Pesa receipt behind two or more payments. */
        DUPLICATE_RECEIPT,
        /** A PayBill payment still sitting unmatched long after it landed. */
        UNAPPLIED_PAYMENT,
        /** A voucher with no payment, no batch and no staff member behind it. */
        SERVICE_NO_PAYMENT,
        /** A hotspot user on the router that matches no voucher we ever issued. */
        GHOST_HOTSPOT_USER,
        /** A PPPoE secret on the router that matches no subscriber. */
        GHOST_PPPOE_SECRET,
        /** A live session on a pass that is spent or expired. */
        EXPIRED_STILL_ONLINE,
        /** A subscriber past their paid-until date who was never suspended. */
        LAPSED_NOT_SUSPENDED,
        /** A sale settled for less than the plan's price. */
        UNDERPAID
    }

    public enum Severity { HIGH, MEDIUM, LOW }

    public enum Status { OPEN, RESOLVED, IGNORED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 220)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    /** What the finding is about — a receipt number, voucher code, username. */
    @Column(nullable = false, length = 200)
    private String subject;

    /** Plain-language explanation shown to the operator. */
    @Column(nullable = false, length = 500)
    private String detail;

    /** Money at stake, where it can be quantified. */
    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    private Instant resolvedAt;

    /** Who closed it — a staff username, or "system" when it cleared itself. */
    @Column(length = 100)
    private String resolvedBy;

    @Column(length = 300)
    private String note;
}
