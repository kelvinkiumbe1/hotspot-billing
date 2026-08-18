package com.spalimited.hotspotbilling.service.i18n;

import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.service.PortalSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The translations themselves.
 *
 * <p>A missing key or a mistyped placeholder does not throw — it ships, and
 * turns up as a French customer reading an English sentence or a code that
 * says "{code}". Both are caught here instead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessagesTest {

    @Mock
    private PortalSettingsService portalSettings;

    @InjectMocks
    private Messages messages;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

    private void operatorSpeaks(String language, boolean followCustomer) {
        when(portalSettings.settings()).thenReturn(PortalSettings.builder()
                .language(language).followCustomerLanguage(followCustomer).build());
    }

    @Test
    @DisplayName("Every language has every key — a gap would ship as an English sentence mid-flow")
    void noLanguageIsMissingAKey() {
        Set<String> english = Messages.catalogue().get(Language.EN).keySet();

        for (Language language : Language.values()) {
            assertThat(Messages.catalogue().get(language))
                    .as("no bundle at all for %s", language.englishName())
                    .isNotNull();
            assertThat(Messages.catalogue().get(language).keySet())
                    .as("%s is missing keys", language.englishName())
                    .containsAll(english);
        }
    }

    @Test
    @DisplayName("No language has invented a key the others do not have")
    void noStrayKeys() {
        Set<String> english = Messages.catalogue().get(Language.EN).keySet();
        for (Language language : Language.values()) {
            assertThat(english)
                    .as("%s has a key English does not, so it can never be reached by fallback",
                            language.englishName())
                    .containsAll(Messages.catalogue().get(language).keySet());
        }
    }

    @Test
    @DisplayName("Every translation keeps the placeholders the English one has")
    void placeholdersSurviveTranslation() {
        Map<String, String> english = Messages.catalogue().get(Language.EN);

        for (Language language : Language.values()) {
            for (Map.Entry<String, String> entry : english.entrySet()) {
                String translated = Messages.catalogue().get(language).get(entry.getKey());
                assertThat(placeholders(translated))
                        .as("%s / %s dropped or renamed a placeholder — the customer would read "
                                + "a literal {brace} where their code should be",
                                language.englishName(), entry.getKey())
                        .isEqualTo(placeholders(entry.getValue()));
            }
        }
    }

    private static Set<String> placeholders(String body) {
        Set<String> found = new java.util.TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(body == null ? "" : body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("A language code with a region resolves to the language")
    void regionCodesResolve() {
        assertThat(Language.of("fr-CI")).isEqualTo(Language.FR);
        assertThat(Language.of("pt_BR")).isEqualTo(Language.PT);
        assertThat(Language.of("EN-GB")).isEqualTo(Language.EN);
        assertThat(Language.of("sw")).isEqualTo(Language.SW);
    }

    @Test
    @DisplayName("A language we do not have falls back to English rather than failing")
    void unknownFallsBack() {
        assertThat(Language.of("de")).isEqualTo(Language.EN);
        assertThat(Language.of("")).isEqualTo(Language.EN);
        assertThat(Language.of(null)).isEqualTo(Language.EN);
    }

    @Test
    @DisplayName("Accept-Language quality values are honoured, not just the first entry")
    void acceptLanguageQuality() {
        // A naive reader takes the first and gets English; the browser said
        // it would rather have French.
        assertThat(Language.fromAcceptHeader("en;q=0.5, fr;q=0.9")).isEqualTo(Language.FR);
        assertThat(Language.fromAcceptHeader("fr-CI,fr;q=0.9,en;q=0.8")).isEqualTo(Language.FR);
        assertThat(Language.fromAcceptHeader("sw-KE,sw;q=0.9")).isEqualTo(Language.SW);
    }

    @Test
    @DisplayName("A header asking only for languages we lack means English, not a wrong guess")
    void acceptLanguageUnknownOnly() {
        // "de" is unknown; without care, of() would map it to English and a
        // naive first-match would report a confident match on a language the
        // browser never asked for.
        assertThat(Language.fromAcceptHeader("de-DE,de;q=0.9")).isEqualTo(Language.EN);
        assertThat(Language.fromAcceptHeader("")).isEqualTo(Language.EN);
        assertThat(Language.fromAcceptHeader(null)).isEqualTo(Language.EN);
    }

    @Test
    @DisplayName("A customer's preference wins when the operator allows it")
    void customerPreferenceHonoured() {
        operatorSpeaks("en", true);
        assertThat(messages.forCustomer("fr")).isEqualTo(Language.FR);
        assertThat(messages.get(messages.forCustomer("fr"), "ussd.thanks")).isEqualTo("Merci.");
    }

    @Test
    @DisplayName("The operator's choice stands when they have turned that off")
    void operatorCanOverride() {
        operatorSpeaks("sw", false);
        // Some deployments genuinely want one language on everything, and
        // silently ignoring that setting would be its own bug.
        assertThat(messages.forCustomer("fr")).isEqualTo(Language.SW);
        assertThat(messages.get("ussd.thanks")).isEqualTo("Asante.");
    }

    @Test
    @DisplayName("Placeholders are filled in whichever language is used")
    void placeholdersFill() {
        operatorSpeaks("pt", true);
        String body = messages.get("ussd.yourCode",
                Map.of("code", "ABC123", "plan", "1 hora", "state", "ativo"));

        assertThat(body).startsWith("O seu código é ABC123 (1 hora, ativo).");
        assertThat(body).doesNotContain("{");
    }

    @Test
    @DisplayName("An unknown key returns nothing rather than showing the key to a customer")
    void unknownKeyIsSilent() {
        operatorSpeaks("fr", true);
        assertThat(messages.get("nothing.like.this")).isEmpty();
    }
}
