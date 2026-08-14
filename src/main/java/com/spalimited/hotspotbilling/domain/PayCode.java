package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The account number the captive portal tells one device to type when paying
 * the paybill by hand.
 *
 * <p>A plain paybill payment carries almost nothing we can act on: a phone
 * number, an amount, and whatever the customer typed in the account field.
 * Handing each device a short code to type turns that free-text field into an
 * exact identifier, which is what lets the payment be matched back to the
 * device sitting on the hotspot and that device let straight on.
 */
@Entity
@Table(name = "pay_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PayCode {

    @Id
    @Column(length = 16)
    private String code;

    /** The device that was shown this code; null when the portal had no MAC. */
    @Column(length = 32)
    private String macAddress;

    private Long routerId;

    /** The pass this code eventually produced, once it has been paid for. */
    @Column(length = 64)
    private String voucherCode;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Transient
    public boolean isSpent() {
        return usedAt != null;
    }
}
