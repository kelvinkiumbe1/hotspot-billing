package com.spalimited.hotspotbilling.service.tax;

import com.spalimited.hotspotbilling.domain.TaxInvoice;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The regimes Zidi can currently issue a receipt under.
 *
 * <p>Kenya was here first and is unchanged by this — an existing install signs
 * exactly the numbers it signed before, which is the point of the deterministic
 * signature: a receipt already in a customer's hand must still verify.
 */
public final class FiscalRegimes {

    private FiscalRegimes() {
    }

    /**
     * A signature over the parts of a sale that identify it.
     *
     * <p>Deterministic on purpose. A retry after a timeout must land on the same
     * number, or one sale ends up with two receipts and the operator's return
     * stops matching their bank.
     */
    private static String signatureOf(String prefix, TaxInvoice invoice, String taxId,
                                      String deviceId) {
        String material = prefix + "|" + invoice.getId() + "|"
                + (invoice.getAmount() == null ? "0" : invoice.getAmount().toPlainString()) + "|"
                + safe(invoice.getCustomerPhone()) + "|" + safe(taxId) + "|" + safe(deviceId);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(
                    digest.digest(material.getBytes(StandardCharsets.UTF_8)));
            return hex.substring(0, 16).toUpperCase(Locale.ROOT);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** Kenya: KRA eTIMS, via an OSCU or VSCU control unit. */
    public static final FiscalRegime KRA = new FiscalRegime() {
        @Override
        public String code() {
            return "KRA";
        }

        @Override
        public String label() {
            return "Kenya — KRA eTIMS";
        }

        @Override
        public String taxIdLabel() {
            return "KRA PIN";
        }

        @Override
        public BigDecimal defaultVatRate() {
            return new BigDecimal("16.00");
        }

        @Override
        public void sign(TaxInvoice invoice, String taxId, String deviceId) {
            String sig = signatureOf("KRA", invoice, taxId, deviceId);
            invoice.setFiscalNumber(String.format(Locale.ROOT, "KRA-%08d", invoice.getId()));
            invoice.setControlUnitNumber(deviceId);
            invoice.setSignature(sig);
            invoice.setVerifyUrl(
                    "https://itax.kra.go.ke/KRA-Portal/invoiceChk.htm?invoiceNo=" + sig);
            invoice.setQrData(invoice.getVerifyUrl());
        }
    };

    /**
     * Nigeria: FIRS e-invoicing.
     *
     * <p>FIRS issues an Invoice Reference Number against a registered supplier
     * TIN, and a receipt is checked on their portal by that reference. VAT is
     * 7.5%, which catches operators out who assume the Kenyan 16%.
     */
    public static final FiscalRegime FIRS = new FiscalRegime() {
        @Override
        public String code() {
            return "FIRS";
        }

        @Override
        public String label() {
            return "Nigeria — FIRS e-invoicing";
        }

        @Override
        public String taxIdLabel() {
            return "TIN";
        }

        @Override
        public BigDecimal defaultVatRate() {
            return new BigDecimal("7.50");
        }

        @Override
        public void sign(TaxInvoice invoice, String taxId, String deviceId) {
            String sig = signatureOf("FIRS", invoice, taxId, deviceId);
            // FIRS references carry the supplier's TIN, so two suppliers cannot
            // collide on a sequence number.
            invoice.setFiscalNumber(String.format(Locale.ROOT, "IRN-%s-%08d",
                    shortTin(taxId), invoice.getId()));
            invoice.setControlUnitNumber(deviceId);
            invoice.setSignature(sig);
            invoice.setVerifyUrl("https://einvoice.firs.gov.ng/verify?irn="
                    + invoice.getFiscalNumber());
            invoice.setQrData(invoice.getVerifyUrl());
        }

        private String shortTin(String taxId) {
            String digits = taxId == null ? "" : taxId.replaceAll("[^0-9]", "");
            if (digits.isBlank()) {
                return "NOTIN";
            }
            return digits.length() <= 8 ? digits : digits.substring(0, 8);
        }
    };

    /**
     * Tanzania: TRA, through a virtual fiscal device.
     *
     * <p>TRA's receipts carry a receipt code and a verification code that a
     * customer types into the TRA portal — the verification code, not the receipt
     * number, is what they check, which is the opposite way round from Kenya.
     * VAT is 18%.
     */
    public static final FiscalRegime TRA = new FiscalRegime() {
        @Override
        public String code() {
            return "TRA";
        }

        @Override
        public String label() {
            return "Tanzania — TRA (virtual fiscal device)";
        }

        @Override
        public String taxIdLabel() {
            return "TIN";
        }

        @Override
        public BigDecimal defaultVatRate() {
            return new BigDecimal("18.00");
        }

        @Override
        public void sign(TaxInvoice invoice, String taxId, String deviceId) {
            String sig = signatureOf("TRA", invoice, taxId, deviceId);
            invoice.setFiscalNumber(String.format(Locale.ROOT, "%s-%08d",
                    deviceId == null || deviceId.isBlank() ? "VFD" : deviceId, invoice.getId()));
            invoice.setControlUnitNumber(deviceId);
            invoice.setSignature(sig);
            // The customer checks the verification code, so it is what goes in
            // the QR rather than the receipt number.
            invoice.setVerifyUrl("https://verify.tra.go.tz/" + sig);
            invoice.setQrData(invoice.getVerifyUrl());
        }
    };

    private static final Map<String, FiscalRegime> BY_CODE = new LinkedHashMap<>();

    static {
        for (FiscalRegime regime : List.of(KRA, FIRS, TRA)) {
            BY_CODE.put(regime.code(), regime);
        }
    }

    public static List<FiscalRegime> all() {
        return List.copyOf(BY_CODE.values());
    }

    /**
     * The regime for a code.
     *
     * <p>Falls back to Kenya rather than throwing, because an unrecognised code
     * in the settings row must not stop an ISP from taking money. A receipt under
     * the wrong regime is a problem; a sale that cannot complete is a worse one.
     */
    public static FiscalRegime byCode(String code) {
        if (code == null || code.isBlank()) {
            return KRA;
        }
        return BY_CODE.getOrDefault(code.trim().toUpperCase(Locale.ROOT), KRA);
    }

    /** Whether a code names a regime we actually have. */
    public static boolean known(String code) {
        return code != null && BY_CODE.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }

    /** Stamps the signing time. Separate so every regime records it the same way. */
    public static void markSigned(TaxInvoice invoice, FiscalRegime regime) {
        invoice.setRegime(regime.code());
        invoice.setStatus(TaxInvoice.Status.SIGNED);
        invoice.setSignedAt(Instant.now());
    }
}
