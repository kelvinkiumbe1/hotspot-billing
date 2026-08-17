package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * How an operator's money reads. The default must be indistinguishable from
 * what Kenyan deployments print today — a currency change is not something an
 * upgrade gets to do quietly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MoneyServiceTest {

    @Mock private PortalSettingsService portalSettings;

    private MoneyService money;
    private PortalSettings settings;

    @BeforeEach
    void setUp() {
        money = new MoneyService(portalSettings);
        settings = PortalSettings.builder().id(1L).businessName("SPA WiFi").build();
        when(portalSettings.settings()).thenReturn(settings);
    }

    @Test
    @DisplayName("Out of the box it reads exactly as Kenya reads it today")
    void defaultsToShillings() {
        assertThat(money.code()).isEqualTo("KES");
        assertThat(money.format(new BigDecimal("500"))).isEqualTo("KES 500");
        assertThat(money.format(new BigDecimal("1200"))).isEqualTo("KES 1,200");
    }

    @Test
    @DisplayName("A glyph sits against the number; a letter code takes a space")
    void spacesLettersButNotGlyphs() {
        settings.setCurrencyCode("NGN");
        settings.setCurrencySymbol("₦");
        assertThat(money.format(new BigDecimal("1200"))).isEqualTo("₦1,200");

        settings.setCurrencySymbol(null);
        assertThat(money.format(new BigDecimal("1200"))).isEqualTo("NGN 1,200");
    }

    @Test
    @DisplayName("Currencies that trail their unit are written that way")
    void supportsASuffixCurrency() {
        settings.setCurrencyCode("XOF");
        settings.setCurrencySymbol("FCFA");
        settings.setCurrencySuffix(true);
        assertThat(money.format(new BigDecimal("2500"))).isEqualTo("2,500 FCFA");
    }

    @Test
    @DisplayName("Currencies with cents keep them; shillings do not grow them")
    void respectsDecimals() {
        settings.setCurrencyCode("USD");
        settings.setCurrencySymbol("$");
        settings.setCurrencyDecimals(2);
        assertThat(money.format(new BigDecimal("12.5"))).isEqualTo("$12.50");

        settings.setCurrencyCode("KES");
        settings.setCurrencySymbol(null);
        settings.setCurrencyDecimals(0);
        assertThat(money.format(new BigDecimal("12.5"))).isEqualTo("KES 13");
    }

    @Test
    @DisplayName("Thousands are grouped, because a misread balance is expensive")
    void groupsThousands() {
        assertThat(money.format(new BigDecimal("1234567"))).isEqualTo("KES 1,234,567");
        assertThat(money.plain(new BigDecimal("1234567"))).isEqualTo("1,234,567");
    }

    @Test
    @DisplayName("Nothing and null are zero, not a crash in a customer's message")
    void handlesNothing() {
        assertThat(money.format(null)).isEqualTo("KES 0");
        assertThat(money.format(BigDecimal.ZERO)).isEqualTo("KES 0");
    }
}
