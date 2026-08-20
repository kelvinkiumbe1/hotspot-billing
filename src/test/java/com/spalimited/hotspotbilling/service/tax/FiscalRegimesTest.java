package com.spalimited.hotspotbilling.service.tax;

import com.spalimited.hotspotbilling.domain.TaxInvoice;
import com.spalimited.hotspotbilling.domain.TaxSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issuing a receipt in three countries.
 *
 * <p>This is a legal gate, not a feature: an ISP in Lagos cannot hand a customer
 * a receipt at all unless it is filed with FIRS, so a billing system that only
 * speaks to KRA is unsellable there whatever else it does.
 *
 * <p>The tests that matter most are the boring ones. A signature has to be
 * deterministic or a retry after a timeout mints a second receipt for one sale
 * and the operator's return stops matching their bank. And the VAT rates differ
 * enough between these three — 16, 7.5 and 18 — that assuming Kenya's is a
 * misstated return every month.
 */
class FiscalRegimesTest {

    private TaxInvoice invoice(long id, String amount) {
        return TaxInvoice.builder()
                .id(id)
                .source(TaxInvoice.Source.HOTSPOT)
                .customerPhone("254712345678")
                .description("6 Hours")
                .amount(new BigDecimal(amount))
                .status(TaxInvoice.Status.PENDING)
                .build();
    }

    private TaxSettings settings(boolean vatOn, String rate, boolean inclusive) {
        return TaxSettings.builder()
                .id(1L)
                .vatEnabled(vatOn)
                .vatRate(new BigDecimal(rate))
                .pricesIncludeVat(inclusive)
                .build();
    }

    // --- each authority's own shape ---

    @Test
    @DisplayName("Kenya signs an eTIMS number and links to the iTax checker")
    void kenya() {
        TaxInvoice inv = invoice(42, "100");

        FiscalRegimes.KRA.sign(inv, "P051234567X", "OSCU-01");

        assertThat(inv.getFiscalNumber()).isEqualTo("KRA-00000042");
        assertThat(inv.getControlUnitNumber()).isEqualTo("OSCU-01");
        assertThat(inv.getVerifyUrl()).contains("itax.kra.go.ke");
        assertThat(inv.getVerifyUrl()).endsWith(inv.getSignature());
    }

    @Test
    @DisplayName("Nigeria's reference carries the supplier TIN, so two suppliers cannot collide")
    void nigeria() {
        TaxInvoice inv = invoice(42, "100");

        FiscalRegimes.FIRS.sign(inv, "12345678-0001", "FIRS-DEV-1");

        assertThat(inv.getFiscalNumber()).isEqualTo("IRN-12345678-00000042");
        assertThat(inv.getVerifyUrl()).contains("firs.gov.ng");
        assertThat(inv.getVerifyUrl()).contains("IRN-12345678-00000042");
    }

    @Test
    @DisplayName("a Nigerian operator with no TIN yet still gets a usable reference")
    void nigeriaWithoutTin() {
        TaxInvoice inv = invoice(42, "100");

        FiscalRegimes.FIRS.sign(inv, null, "FIRS-DEV-1");

        // Nothing here should throw during a sale, and a placeholder is easier to
        // spot in a list than a blank.
        assertThat(inv.getFiscalNumber()).isEqualTo("IRN-NOTIN-00000042");
    }

    @Test
    @DisplayName("Tanzania puts the verification code in the QR, not the receipt number")
    void tanzania() {
        TaxInvoice inv = invoice(42, "100");

        FiscalRegimes.TRA.sign(inv, "123-456-789", "VFD-77");

        assertThat(inv.getFiscalNumber()).isEqualTo("VFD-77-00000042");
        // TRA customers check the verification code, which is the opposite way
        // round from Kenya.
        assertThat(inv.getQrData()).endsWith(inv.getSignature());
        assertThat(inv.getVerifyUrl()).contains("tra.go.tz");
    }

    // --- the properties that keep the books straight ---

    @Test
    @DisplayName("signing twice gives the same number, so a retry cannot mint a second receipt")
    void signingIsDeterministic() {
        TaxInvoice first = invoice(42, "100");
        TaxInvoice second = invoice(42, "100");

        FiscalRegimes.KRA.sign(first, "P051234567X", "OSCU-01");
        FiscalRegimes.KRA.sign(second, "P051234567X", "OSCU-01");

        // A timeout followed by a retry is the normal case, not the rare one.
        assertThat(first.getSignature()).isEqualTo(second.getSignature());
        assertThat(first.getFiscalNumber()).isEqualTo(second.getFiscalNumber());
    }

    @Test
    @DisplayName("two different sales do not sign to the same number")
    void differentSalesDifferentSignatures() {
        TaxInvoice a = invoice(42, "100");
        TaxInvoice b = invoice(43, "100");

        FiscalRegimes.KRA.sign(a, "P051234567X", "OSCU-01");
        FiscalRegimes.KRA.sign(b, "P051234567X", "OSCU-01");

        assertThat(a.getSignature()).isNotEqualTo(b.getSignature());
    }

    @Test
    @DisplayName("the same sale under two authorities does not share a signature")
    void regimesDoNotShareSignatures() {
        TaxInvoice ke = invoice(42, "100");
        TaxInvoice ng = invoice(42, "100");

        FiscalRegimes.KRA.sign(ke, "SAME", "SAME");
        FiscalRegimes.FIRS.sign(ng, "SAME", "SAME");

        assertThat(ke.getSignature()).isNotEqualTo(ng.getSignature());
    }

    @Test
    @DisplayName("the amount is part of the signature, so an edited invoice does not verify")
    void amountIsSigned() {
        TaxInvoice original = invoice(42, "100");
        TaxInvoice altered = invoice(42, "10");

        FiscalRegimes.KRA.sign(original, "P051234567X", "OSCU-01");
        FiscalRegimes.KRA.sign(altered, "P051234567X", "OSCU-01");

        assertThat(original.getSignature()).isNotEqualTo(altered.getSignature());
    }

    // --- picking one ---

    @Test
    @DisplayName("an unrecognised regime falls back to Kenya rather than stopping a sale")
    void unknownRegimeFallsBack() {
        // A wrong code in the settings row is a problem. A sale that cannot
        // complete because of one is a worse problem.
        assertThat(FiscalRegimes.byCode("NONSENSE").code()).isEqualTo("KRA");
        assertThat(FiscalRegimes.byCode(null).code()).isEqualTo("KRA");
        assertThat(FiscalRegimes.byCode("  ").code()).isEqualTo("KRA");
        assertThat(FiscalRegimes.known("NONSENSE")).isFalse();
        assertThat(FiscalRegimes.known("firs")).isTrue();
    }

    @Test
    @DisplayName("each authority asks for its own identifier by its own name")
    void identifiersAreNamedLocally() {
        assertThat(FiscalRegimes.KRA.taxIdLabel()).isEqualTo("KRA PIN");
        assertThat(FiscalRegimes.FIRS.taxIdLabel()).isEqualTo("TIN");
        assertThat(FiscalRegimes.TRA.taxIdLabel()).isEqualTo("TIN");
    }

    @Test
    @DisplayName("the default rates are each country's own, not Kenya's everywhere")
    void ratesDifferByCountry() {
        // Assuming 16% in Lagos misstates every return by more than half the VAT.
        assertThat(FiscalRegimes.KRA.defaultVatRate()).isEqualByComparingTo("16.00");
        assertThat(FiscalRegimes.FIRS.defaultVatRate()).isEqualByComparingTo("7.50");
        assertThat(FiscalRegimes.TRA.defaultVatRate()).isEqualByComparingTo("18.00");
    }

    @Test
    @DisplayName("no regime claims it can file live yet")
    void nothingPretendsToFile() {
        // Every one needs a registered device and credentials. A receipt that
        // says it was filed when it was not is the one failure an operator
        // cannot recover from at audit.
        assertThat(FiscalRegimes.all()).allMatch(r -> !r.canFileLive());
    }

    // --- the money ---

    @Test
    @DisplayName("VAT inside a price is not the same sum as VAT on top of it")
    void inclusiveAndExclusiveDiffer() {
        BigDecimal gross = new BigDecimal("116.00");

        BigDecimal inside = FiscalService.vatOn(gross, settings(true, "16", true));
        BigDecimal onTop = FiscalService.vatOn(gross, settings(true, "16", false));

        // Getting these the wrong way round misstates a return by the whole
        // difference, every month, quietly.
        assertThat(inside).isEqualByComparingTo("16.00");
        assertThat(onTop).isEqualByComparingTo("18.56");
    }

    @Test
    @DisplayName("each country's rate comes out right on an inclusive price")
    void ratesComputeCorrectly() {
        assertThat(FiscalService.vatOn(new BigDecimal("1075"), settings(true, "7.5", true)))
                .isEqualByComparingTo("75.00");
        assertThat(FiscalService.vatOn(new BigDecimal("1180"), settings(true, "18", true)))
                .isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("VAT switched off means zero, not a silent default rate")
    void vatOffMeansZero() {
        assertThat(FiscalService.vatOn(new BigDecimal("100"), settings(false, "16", true)))
                .isEqualByComparingTo("0");
        assertThat(FiscalService.vatOn(new BigDecimal("100"), settings(true, "0", true)))
                .isEqualByComparingTo("0");
        assertThat(FiscalService.vatOn(null, settings(true, "16", true)))
                .isEqualByComparingTo("0");
    }
}
