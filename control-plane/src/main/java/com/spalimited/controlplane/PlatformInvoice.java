package com.spalimited.controlplane;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One charge for the Zidi platform fee owed by an ISP. The tenant computes the
 * amount from its own revenue (the control plane can't see that) and asks the
 * control plane to collect it; this row tracks that collection to completion.
 */
@Entity
@Table(name = "platform_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformInvoice {

    public enum Status {
        /** STK sent, waiting for the owner to enter their M-Pesa PIN. */
        PENDING,
        /** Paid — M-Pesa confirmed. */
        PAID,
        /** The customer cancelled, timed out, or the push failed. */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String tenantSlug;

    /** Billing period, e.g. "2026-08". One paid invoice per period settles it. */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** The M-Pesa number the STK was sent to (2547XXXXXXXX). */
    @Column(length = 15)
    private String phone;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    /** Daraja CheckoutRequestID, so a callback can find its invoice. */
    @Column(length = 64)
    private String checkoutId;

    /** M-Pesa receipt number once paid. */
    @Column(length = 32)
    private String mpesaReceipt;

    @Column(length = 255)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant paidAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
