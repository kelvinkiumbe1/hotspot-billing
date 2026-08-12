package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * How an operator collects money. Credentials live here rather than in
 * environment variables so each operator can set up their own gateway from
 * the admin, without anyone editing a config file and restarting for them.
 *
 * <p>Exactly one gateway is active at a time. The others keep their saved
 * credentials, so switching back is one click rather than a re-entry.
 */
@Entity
@Table(name = "payment_gateways")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentGateway {

    /**
     * Only kinds that genuinely work are here. A gateway that cannot
     * actually take money has no business appearing as an option — that is
     * the failure mode where everything looks fine and nothing collects.
     */
    public enum Kind {
        /** Daraja STK push plus C2B confirmation. Fully automatic. */
        MPESA_API,
        /** A paybill with no API access; payments are reconciled by hand. */
        MPESA_PAYBILL_MANUAL,
        /** A Buy Goods till with no API access; reconciled by hand. */
        MPESA_TILL_MANUAL,
        /** Bank transfer; reconciled by hand. */
        BANK_TRANSFER
    }

    public enum Environment { SANDBOX, PRODUCTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private Kind kind;

    @Builder.Default
    @Column(nullable = false)
    private boolean active = false;

    // --- Daraja (MPESA_API only) ---

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private Environment environment = Environment.SANDBOX;

    private String consumerKey;

    private String consumerSecret;

    /** The shortcode STK pushes are billed to. */
    private String shortCode;

    private String passkey;

    /** Initiator username for the Transaction Status API (verifying M-Pesa codes). */
    private String initiatorName;

    /** Initiator password encrypted with Safaricom's public cert; long base64. */
    @Column(length = 2048)
    private String securityCredential;

    // --- Manual gateways ---

    /** Paybill number, or the till number for Buy Goods. */
    private String paybillNumber;

    private String tillNumber;

    private String bankName;

    private String accountNumber;

    private String accountName;

    /**
     * What the customer is told on the portal. Free text because every
     * operator words this differently.
     */
    @Column(length = 1000)
    private String instructions;

    private String updatedBy;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = Instant.now();
    }

    /** Whether this gateway has everything it needs to actually collect. */
    @Transient
    public boolean isConfigured() {
        return switch (kind) {
            case MPESA_API -> filled(consumerKey) && filled(consumerSecret)
                    && filled(shortCode) && filled(passkey);
            case MPESA_PAYBILL_MANUAL -> filled(paybillNumber);
            case MPESA_TILL_MANUAL -> filled(tillNumber);
            case BANK_TRANSFER -> filled(bankName) && filled(accountNumber);
        };
    }

    /**
     * True when money arrives without anyone doing anything. The manual
     * kinds still need a person to match a payment to a customer, which is
     * worth saying plainly in the admin rather than implying automation.
     */
    @Transient
    public boolean isAutomatic() {
        return kind == Kind.MPESA_API;
    }

    /** Live money, as opposed to Safaricom's sandbox. */
    @Transient
    public boolean isLive() {
        return kind != Kind.MPESA_API || environment == Environment.PRODUCTION;
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
