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

import java.math.BigDecimal;

/**
 * Who may take WiFi on credit and on what terms. Single row (id = 1).
 *
 * <p>Off by default. Lending is the operator's money at risk, so nothing here
 * starts switched on and every limit is theirs to set.
 */
@Entity
@Table(name = "credit_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreditSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = false;

    /** Passes the customer must have paid for before any credit is offered. */
    @Builder.Default
    @Column(nullable = false)
    private int minPurchases = 3;

    /** And how long they must have been buying — one good day proves nothing. */
    @Builder.Default
    @Column(nullable = false)
    private int minDaysKnown = 7;

    /** The dearest pass that may be taken on credit. */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal maxAdvance = BigDecimal.valueOf(100);

    /** A service fee on the advance, as a percentage. Zero is a fair default. */
    @Builder.Default
    @Column(nullable = false)
    private int feePercent = 0;

    /** How long the customer has to settle before it counts as missed. */
    @Builder.Default
    @Column(nullable = false)
    private int repayWithinHours = 48;

    /** Missed repayments before that customer is cut off from credit. */
    @Builder.Default
    @Column(nullable = false)
    private int maxDefaults = 1;
}
