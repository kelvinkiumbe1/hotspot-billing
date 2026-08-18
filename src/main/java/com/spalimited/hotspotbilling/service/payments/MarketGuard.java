package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.service.i18n.Country;
import lombok.extern.slf4j.Slf4j;

/**
 * Refuses to charge in a currency the price was not written in.
 *
 * <p>Airtel Money, Orange Money and Wave each collect in their market's own
 * currency and take the amount as a bare number. Everything else in this system
 * prices plans in whatever currency the operator configured. Those are two
 * separate settings — choosing a country in the admin fills the currency in, but
 * the field stays editable and the API accepts either on its own — so they can
 * disagree.
 *
 * <p>When they do, the failure is silent and it is about money. A plan priced
 * "1000" and displayed as KES 1,000 goes to Wave as 1000 XOF, which is roughly a
 * fifth of it. Nothing errors; the customer is simply charged the wrong amount,
 * and the operator finds out from their settlement report.
 *
 * <p>So a rail whose market currency does not match the configured one is
 * treated as unusable. Customers are not offered it, which is right — it cannot
 * take their money correctly — and the log says exactly which two settings
 * disagree, because "Wave isn't showing up" is otherwise unguessable.
 */
@Slf4j
final class MarketGuard {

    private MarketGuard() {
    }

    /**
     * Whether prices in {@code configured} can be collected in {@code country}.
     *
     * <p>A blank configured currency passes: an operator who has not set one is
     * a fresh install rather than a contradiction, and MoneyService has its own
     * default for that.
     */
    static boolean currencyAgrees(String rail, Country country, String configured) {
        if (configured == null || configured.isBlank()) {
            return true;
        }
        if (configured.trim().equalsIgnoreCase(country.currency())) {
            return true;
        }
        log.warn("{} is switched on but not offered: your prices are in {} and {} collects in {}. "
                        + "Set the currency in Branding to {} — otherwise a plan priced 1000 would be "
                        + "charged as 1000 {}.",
                rail, configured, country.countryName(), country.currency(),
                country.currency(), country.currency());
        return false;
    }
}
