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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Turning what a customer typed into one canonical number.
 *
 * <p>The failure mode this guards against is the quietest one in the product:
 * a number that is refused is a customer who cannot pay, and nothing anywhere
 * records that they tried. Five copies of a Kenyan normaliser used to make that
 * certain for every operator outside Kenya.
 *
 * <p>The first group of tests exists to prove nothing changed for Kenya. Every
 * number already stored was produced by the old code, so if this disagreed with
 * it the whole customer base would stop matching overnight.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhoneNumbersTest {

    @Mock
    private PortalSettingsService portalSettings;

    @InjectMocks
    private PhoneNumbers phones;

    private void operatorIn(String country) {
        when(portalSettings.settings()).thenReturn(
                PortalSettings.builder().country(country).build());
    }

    // --- Kenya must be byte-identical to the code this replaced ---

    @Test
    @DisplayName("Kenya: every shape a customer types resolves the way it always did")
    void kenyaUnchanged() {
        operatorIn("KE");

        assertThat(phones.normalise("0712345678")).isEqualTo("254712345678");
        assertThat(phones.normalise("712345678")).isEqualTo("254712345678");
        assertThat(phones.normalise("254712345678")).isEqualTo("254712345678");
        assertThat(phones.normalise("+254712345678")).isEqualTo("254712345678");
        assertThat(phones.normalise("+254 712 345 678")).isEqualTo("254712345678");
        assertThat(phones.normalise("0712-345-678")).isEqualTo("254712345678");
        // Safaricom's newer 011 range, which the old code also accepted.
        assertThat(phones.normalise("0110123456")).isEqualTo("254110123456");
    }

    @Test
    @DisplayName("Kenya: nonsense is still refused")
    void kenyaRejects() {
        operatorIn("KE");

        assertThat(phones.normalise("12345")).isNull();
        assertThat(phones.normalise("07123456789012")).isNull();
        assertThat(phones.normalise("")).isNull();
        assertThat(phones.normalise(null)).isNull();
        assertThat(phones.normalise("not a number")).isNull();
    }

    @Test
    @DisplayName("Kenya: a number of the right length but the wrong prefix is still refused")
    void kenyaChecksThePrefix() {
        operatorIn("KE");

        // The code this replaced required 7 or 1, and dropping that check would
        // have quietly loosened Kenya. 0241234567 is a Ghanaian number; length
        // alone would turn it into a Kenyan one belonging to nobody. Caught
        // only after the first version of this test passed without testing it.
        assertThat(phones.normalise("0241234567")).isNull();
        assertThat(phones.normalise("241234567")).isNull();
        assertThat(phones.normalise("0312345678")).isNull();
    }

    // --- The countries that could not sell at all ---

    @Test
    @DisplayName("Ghana: a 233 number is accepted, which is the whole point")
    void ghana() {
        operatorIn("GH");

        assertThat(phones.normalise("0241234567")).isEqualTo("233241234567");
        assertThat(phones.normalise("241234567")).isEqualTo("233241234567");
        assertThat(phones.normalise("+233 24 123 4567")).isEqualTo("233241234567");
        assertThat(phones.normalise("00233241234567")).isEqualTo("233241234567");
    }

    @Test
    @DisplayName("Nigeria's numbers are a digit longer, and that is handled")
    void nigeria() {
        operatorIn("NG");

        // Ten national digits, not nine. A rule written for Kenya rejects every
        // one of these.
        assertThat(phones.normalise("08031234567")).isEqualTo("2348031234567");
        assertThat(phones.normalise("8031234567")).isEqualTo("2348031234567");
        assertThat(phones.normalise("+234 803 123 4567")).isEqualTo("2348031234567");
        // Nine digits is a Kenyan-length number and wrong here.
        assertThat(phones.normalise("803123456")).isNull();
    }

    @Test
    @DisplayName("South Africa's two-digit dialling code does not confuse the parser")
    void southAfrica() {
        operatorIn("ZA");

        assertThat(phones.normalise("0821234567")).isEqualTo("27821234567");
        assertThat(phones.normalise("821234567")).isEqualTo("27821234567");
        assertThat(phones.normalise("+27 82 123 4567")).isEqualTo("27821234567");
    }

    @Test
    @DisplayName("Côte d'Ivoire moved to ten digits, and the table knows")
    void coteDIvoire() {
        operatorIn("CI");

        assertThat(phones.normalise("0102030405")).isEqualTo("2250102030405");
        assertThat(phones.normalise("+225 01 02 03 04 05")).isEqualTo("2250102030405");
    }

    // --- Borders ---

    @Test
    @DisplayName("A Kenyan operator can reach a Ugandan customer")
    void crossBorder() {
        operatorIn("KE");

        // An ISP near a border has customers on the other side of it. The old
        // code returned null for every one of them, so they were never texted.
        assertThat(phones.normalise("+256772123456")).isEqualTo("256772123456");
        assertThat(phones.normalise("255712345678")).isEqualTo("255712345678");
    }

    @Test
    @DisplayName("A foreign number of the wrong length is still refused")
    void crossBorderStillChecked() {
        operatorIn("KE");

        // Starts with 256 but is too short to be a Ugandan number, and too long
        // to be a Kenyan one — accepting it would just push the failure to the
        // gateway with no explanation.
        assertThat(phones.normalise("2567721")).isNull();
    }

    // --- The unknown case ---

    @Test
    @DisplayName("An operator somewhere we have no rules for is not forced into Kenya's")
    void somewhereElse() {
        operatorIn("OTHER");

        // Silently prefixing "254" onto a Peruvian number would create a real
        // Kenyan number belonging to a stranger.
        assertThat(phones.normalise("+51987654321")).isEqualTo("51987654321");
        assertThat(phones.normalise("987654321")).isEqualTo("987654321");
        assertThat(phones.normalise("12")).isNull();
    }

    @Test
    @DisplayName("Nothing longer than E.164 allows gets through")
    void e164Ceiling() {
        operatorIn("KE");
        assertThat(phones.normalise("1234567890123456")).isNull();
    }

    // --- What the customer is told to type ---

    @Test
    @DisplayName("The example shown to a customer is their country's, not Kenya's")
    void exampleFollowsCountry() {
        operatorIn("KE");
        assertThat(phones.example()).isEqualTo("254XXXXXXXXX");

        operatorIn("GH");
        assertThat(phones.example()).isEqualTo("233XXXXXXXXX");

        operatorIn("NG");
        assertThat(phones.example()).isEqualTo("234XXXXXXXXXX");

        operatorIn("OTHER");
        assertThat(phones.example()).contains("country code");
    }

    @Test
    @DisplayName("A number we cannot read is kept as digits rather than thrown away")
    void looseKeepsEvidence() {
        operatorIn("KE");

        // An unmatched paybill payment is still evidence, even from a number
        // that cannot be parsed.
        assertThat(phones.loose("+1 (555) 010-9999")).isEqualTo("15550109999");
        assertThat(phones.loose("0712345678")).isEqualTo("254712345678");
        assertThat(phones.loose(null)).isEmpty();
    }

    @Test
    @DisplayName("An unreadable settings row falls back to Kenya rather than failing")
    void settingsFailureIsSurvivable() {
        when(portalSettings.settings()).thenThrow(new IllegalStateException("no database"));

        assertThat(phones.country()).isEqualTo(Country.KE);
        assertThat(phones.normalise("0712345678")).isEqualTo("254712345678");
    }
}
