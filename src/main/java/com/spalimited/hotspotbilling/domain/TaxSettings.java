package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * VAT configuration, held as a single row. Whether prices include VAT is
 * the setting that matters most: in Kenya a quoted price normally already
 * contains it, so getting this backwards either overcharges every customer
 * by the VAT rate or under-declares the tax.
 */
@Entity
@Table(name = "tax_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxSettings {

    @Id
    private Long id;

    /** Off for a business below the registration threshold. */
    @Builder.Default
    @Column(nullable = false)
    private boolean vatEnabled = false;

    /** Kenya is 16% at the time of writing; kept configurable because it moves. */
    @Builder.Default
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRate = new BigDecimal("16.00");

    /**
     * True when the prices on packages and subscriptions already contain
     * VAT, which is the normal Kenyan consumer arrangement.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean pricesIncludeVat = true;

    /** Printed on the tax invoice; KRA requires the seller's PIN on it. */
    private String kraPin;

    private String legalName;

    private String addressLine;

    /** Prefix for the invoice number series, e.g. INV. */
    @Builder.Default
    private String invoicePrefix = "INV";

    private Instant updatedAt;

    @PreUpdate
    @PrePersist
    void stamp() {
        updatedAt = Instant.now();
    }

    /** 1.16 for a 16% rate — the divisor when backing VAT out of a gross price. */
    @Transient
    public BigDecimal getGrossMultiplier() {
        return BigDecimal.ONE.add(vatRate.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
    }

    /**
     * Splits a charge into net and VAT.
     *
     * <p>When prices include VAT the amount charged stays exactly what the
     * customer agreed and the tax is backed out of it, so the total on the
     * invoice always matches what they pay. When prices exclude VAT the tax
     * is added on top instead.
     *
     * @param amount the figure held against the package or subscription
     * @return net, vat and gross, each rounded to two decimal places
     */
    public Split split(BigDecimal amount) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        if (!vatEnabled || vatRate.signum() == 0) {
            BigDecimal flat = amount.setScale(2, RoundingMode.HALF_UP);
            return new Split(flat, BigDecimal.ZERO.setScale(2), flat);
        }
        if (pricesIncludeVat) {
            BigDecimal gross = amount.setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = gross.divide(getGrossMultiplier(), 2, RoundingMode.HALF_UP);
            // VAT is the remainder rather than a second rounding, so the
            // three figures always add up.
            return new Split(net, gross.subtract(net), gross);
        }
        BigDecimal net = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal vat = net.multiply(vatRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Split(net, vat, net.add(vat));
    }

    /** Net, VAT and gross for one charge. net + vat == gross, always. */
    public record Split(BigDecimal net, BigDecimal vat, BigDecimal gross) {
    }
}
