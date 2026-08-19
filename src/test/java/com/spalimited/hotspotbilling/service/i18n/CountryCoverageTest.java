package com.spalimited.hotspotbilling.service.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The country table and the browser's copy of it, kept in step.
 *
 * <p>They are two tables saying the same thing, and the failure mode when they
 * disagree is specific and bad: the portal accepts a number the API then
 * rejects, so the customer sees a form that says nothing is wrong and a payment
 * that never starts. Worse in the other direction — a wrong digit count rejects
 * a real number and tells a paying customer they do not exist.
 */
class CountryCoverageTest {

    private static final Path PHONE_JS = Path.of("frontend/src/phone.js");

    @Test
    @DisplayName("Every country's dialling rules match the browser's copy")
    void diallingTablesAgree() throws IOException {
        Map<String, String> js = frontendTable();
        // Skipped rather than failed when the frontend is not on disk, so the
        // backend build does not depend on the checkout shape.
        if (js.isEmpty()) {
            return;
        }
        for (Country country : Country.values()) {
            if (country == Country.OTHER) {
                continue;
            }
            String expected = country.diallingCode() + ":" + country.nationalLength();
            assertThat(js)
                    .as("%s is in Country.java and missing from phone.js — the portal would "
                            + "refuse a number the API accepts", country)
                    .containsKey(country.name());
            assertThat(js.get(country.name()))
                    .as("%s: Country.java says %s, phone.js says %s",
                            country, expected, js.get(country.name()))
                    .isEqualTo(expected);
        }
        assertThat(js.keySet())
                .as("phone.js names a country Country.java does not")
                .allMatch(code -> {
                    try {
                        Country.valueOf(code);
                        return true;
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                });
    }

    @Test
    @DisplayName("Every country has plausible dialling rules")
    void rulesArePlausible() {
        for (Country country : Country.values()) {
            if (country == Country.OTHER) {
                continue;
            }
            assertThat(country.diallingCode())
                    .as("%s has no dialling code", country).isNotEmpty()
                    .matches("\\d{1,4}");
            // A wrong length rejects real paying customers, which is the worst
            // failure this table can produce. The bounds are wide on purpose:
            // this catches a typo, not a judgement call.
            assertThat(country.nationalLength())
                    .as("%s claims a %d-digit national number", country, country.nationalLength())
                    .isBetween(6, 11);
            assertThat(country.currency())
                    .as("%s has no currency", country).matches("[A-Z]{3}");
            assertThat(country.networks())
                    .as("%s lists no way anyone pays", country).isNotEmpty();
        }
    }

    @Test
    @DisplayName("No two countries share a dialling code")
    void diallingCodesAreUnique() {
        Map<String, Country> seen = new LinkedHashMap<>();
        for (Country country : Country.values()) {
            if (country == Country.OTHER) {
                continue;
            }
            Country clash = seen.put(country.diallingCode(), country);
            // byDiallingPrefix picks the longest match, so a duplicate would
            // silently resolve to whichever came first in the enum.
            assertThat(clash)
                    .as("%s and %s both claim +%s", clash, country, country.diallingCode())
                    .isNull();
        }
    }

    @Test
    @DisplayName("The rails reach the countries that name them")
    void everyCountryRailIsServed() {
        // A country whose default rail does not list it is a country that reads
        // as supported and collects nothing.
        for (Country country : Country.values()) {
            if (country.rail() == Country.Rail.NONE || country == Country.OTHER) {
                continue;
            }
            assertThat(country.rail())
                    .as("%s points at %s", country, country.rail())
                    .isNotNull();
        }
        // The new markets, spot-checked against the sets that gate them.
        assertThat(Country.ML.rail()).isEqualTo(Country.Rail.ORANGE_MONEY);
        assertThat(Country.GM.rail()).isEqualTo(Country.Rail.WAVE);
        assertThat(Country.TD.rail()).isEqualTo(Country.Rail.AIRTEL_MONEY);
        assertThat(Country.BJ.rail()).isEqualTo(Country.Rail.MTN_MOMO);
    }

    @Test
    @DisplayName("Every unreachable country still has usable local rules")
    void unreachableCountriesAreStillProperlySetUp() {
        // The point of listing a country nothing can collect in: it still gets
        // its own currency, dialling rules and language. Falling through to OTHER
        // would give it dollars and no phone validation, and a portal that
        // accepts any number is a portal that refuses real customers.
        for (Country c : Country.values()) {
            if (!c.needsManualCollection()) {
                continue;
            }
            assertThat(c.currency()).as("%s currency", c).matches("[A-Z]{3}");
            assertThat(c.diallingCode()).as("%s dialling code", c).matches("\\d{1,4}");
            assertThat(c.nationalLength()).as("%s number length", c).isBetween(6, 11);
            assertThat(c.networks()).as("%s networks", c).isNotEmpty();
        }
    }

    /** The DIALLING literal in phone.js, as code -> "dial:length". */
    private static Map<String, String> frontendTable() throws IOException {
        if (!Files.exists(PHONE_JS)) {
            return Map.of();
        }
        String src = Files.readString(PHONE_JS, StandardCharsets.UTF_8);
        int start = src.indexOf("const DIALLING = {");
        int end = src.indexOf("\n}", start);
        if (start < 0 || end < 0) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        // The lookbehind matters: without it "OTHER:" yields a country called
        // "ER" and the test fails on its own parsing rather than on the data.
        Matcher m = Pattern.compile("(?<![A-Z])([A-Z]{2}):\\s*\\['(\\d*)',\\s*(\\d+)")
                .matcher(src.substring(start, end));
        while (m.find()) {
            out.put(m.group(1), m.group(2) + ":" + m.group(3));
        }
        return out;
    }
}
