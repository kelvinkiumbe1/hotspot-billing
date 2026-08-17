package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * How this operator writes an amount of money.
 *
 * <p>"KES" was written into the source in seventy-five places. That is
 * invisible until the second customer of this software is in Lagos, at which
 * point every message, invoice and menu quotes them a currency they do not
 * use — and there is no setting that fixes it, only a hunt through the code.
 *
 * <p>One place decides now. Everything that shows an amount asks here.
 */
@Service
@RequiredArgsConstructor
public class MoneyService {

    private final PortalSettingsService portalSettings;

    /** The ISO code, for machines: gateways, tax records, ledgers. */
    public String code() {
        String code = portalSettings.settings().getCurrencyCode();
        return code == null || code.isBlank() ? "KES" : code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * An amount as a customer should read it — "KES 1,200", "₦1,200",
     * "$12.50", "1,200 FCFA". Thousands are grouped because a five-figure
     * balance written without separators gets misread, and being misread about
     * money is expensive.
     */
    public String format(BigDecimal amount) {
        PortalSettings s = portalSettings.settings();
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        int decimals = Math.max(0, Math.min(4, s.getCurrencyDecimals()));

        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimals > 0) {
            pattern.append('.').append("0".repeat(decimals));
        }
        String number = new DecimalFormat(pattern.toString(), symbols)
                .format(value.setScale(decimals, RoundingMode.HALF_UP));

        String unit = s.getCurrencySymbol() == null || s.getCurrencySymbol().isBlank()
                ? code() : s.getCurrencySymbol().trim();
        // A letter code needs a space ("KES 500"); a glyph does not ("₦500").
        boolean spaced = unit.chars().anyMatch(Character::isLetter);
        if (s.isCurrencySuffix()) {
            return spaced ? number + " " + unit : number + unit;
        }
        return spaced ? unit + " " + number : unit + number;
    }

    /** The bare number, grouped but with no unit — for a column already headed. */
    public String plain(BigDecimal amount) {
        PortalSettings s = portalSettings.settings();
        int decimals = Math.max(0, Math.min(4, s.getCurrencyDecimals()));
        StringBuilder pattern = new StringBuilder("#,##0");
        if (decimals > 0) {
            pattern.append('.').append("0".repeat(decimals));
        }
        return new DecimalFormat(pattern.toString(), new DecimalFormatSymbols(Locale.ROOT))
                .format((amount == null ? BigDecimal.ZERO : amount)
                        .setScale(decimals, RoundingMode.HALF_UP));
    }
}
