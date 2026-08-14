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

/**
 * Zero-touch paybill activation. Single row (id = 1).
 */
@Entity
@Table(name = "paybill_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaybillSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Turn a plain paybill payment into a hotspot pass automatically. */
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Also let the paying device straight onto the network without it having
     * to type the code, by adding a MikroTik user named after its MAC.
     *
     * <p>Off by default: it only works once the hotspot server profile has
     * {@code login-by=mac} enabled on the router, which is the operator's
     * call to make. The code still goes out by WhatsApp/SMS either way, so
     * the customer is never stranded if this is off or the router rejects it.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean autoLoginByMac = false;

    /** How long a pay code stays valid for its device. */
    @Builder.Default
    @Column(nullable = false)
    private int payCodeMinutes = 120;

    /** Text the customer when they've sent less than the cheapest pass costs. */
    @Builder.Default
    @Column(nullable = false)
    private boolean notifyOnShortfall = true;

    /**
     * Don't auto-issue above this amount — leave it for a human.
     *
     * <p>Somebody the system doesn't recognise sending far more than any pass
     * costs is usually a mistake: a wrong account number on a monthly bill, a
     * typo'd amount. Guessing a pass for it would quietly keep the difference,
     * so past this ceiling the payment stays in the unmatched queue where the
     * operator can see and place it.
     */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private java.math.BigDecimal maxAmount = java.math.BigDecimal.valueOf(3000);
}
