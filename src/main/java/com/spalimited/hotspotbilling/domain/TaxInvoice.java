package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A KRA tax invoice for a completed sale. Kenya's eTIMS requires every sale to
 * be fiscalised — signed by KRA and stamped with a control-unit number and a
 * verifiable signature. This row is created on each successful payment and then
 * "signed" by the {@code EtimsService} (a sandbox/dry-run signer until real KRA
 * credentials and device registration are wired in).
 */
@Entity
@Table(name = "tax_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxInvoice {

    public enum Source { HOTSPOT, SUBSCRIPTION }

    public enum Status { PENDING, SIGNED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source;

    private String customerPhone;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    /** KRA's invoice number returned on signing. */
    private String kraInvoiceNumber;

    /** The signing control unit (SCU/CU) identifier. */
    private String controlUnitNumber;

    /** KRA receipt signature. */
    private String signature;

    /** Content of the verification QR (a KRA checker URL). */
    @Column(length = 512)
    private String qrData;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant signedAt;
}
