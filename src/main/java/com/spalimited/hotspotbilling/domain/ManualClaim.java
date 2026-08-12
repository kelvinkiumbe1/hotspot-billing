package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A customer's attempt to claim access with an M-Pesa confirmation code, for
 * money paid outside the STK flow (a Paybill/Till payment, or an STK whose
 * voucher never arrived). The code is verified against Safaricom before any
 * voucher is issued; {@code receipt} is unique so a code can be claimed once.
 */
@Entity
@Table(name = "manual_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualClaim {

    public enum Status { PENDING, RESOLVED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The M-Pesa confirmation code the customer entered. Unique = claim-once. */
    @Column(nullable = false, unique = true, length = 32)
    private String receipt;

    /** The payer's number, taken from Safaricom's verified record on resolve. */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    /** The plan the verified amount matched, once resolved. */
    @Column(name = "plan_id")
    private Long planId;

    /** Daraja ConversationID, to correlate the async status result. */
    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "voucher_id")
    private Long voucherId;

    @Column(length = 255)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
