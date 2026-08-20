package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PhoneVerification;
import com.spalimited.hotspotbilling.domain.PortalSettings;
import com.spalimited.hotspotbilling.repository.PhoneVerificationRepository;
import com.spalimited.hotspotbilling.service.i18n.PhoneNumbers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirming a customer's phone number.
 *
 * <p>A six-digit code is only worth anything if guessing is bounded, so most of
 * these tests are about the bounds rather than the happy path: attempts counted,
 * requests limited per number and per address, and a dead challenge staying dead.
 * Get those wrong and this is theatre that costs the operator a text per attempt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhoneVerificationServiceTest {

    @Mock
    private PhoneVerificationRepository verifications;

    @Mock
    private SmsService smsService;

    @Mock
    private PortalSettingsService portalSettings;

    @Mock
    private PhoneNumbers phoneNumbers;

    @InjectMocks
    private PhoneVerificationService service;

    private final List<PhoneVerification> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stored.clear();

        PortalSettings settings = new PortalSettings();
        settings.setBusinessName("SPA WiFi");
        when(portalSettings.settings()).thenReturn(settings);

        when(phoneNumbers.normalise(anyString())).thenAnswer(i -> {
            String raw = i.getArgument(0);
            if (raw == null) {
                return null;
            }
            String digits = raw.replaceAll("\\D", "");
            if (digits.startsWith("0") && digits.length() == 10) {
                return "254" + digits.substring(1);
            }
            return digits.isBlank() ? null : digits;
        });
        when(phoneNumbers.isValid(anyString())).thenAnswer(i ->
                ((String) i.getArgument(0)).replaceAll("\\D", "").length() >= 9);

        when(verifications.save(any())).thenAnswer(i -> {
            PhoneVerification v = i.getArgument(0);
            if (v.getId() == null) {
                v.setId((long) (stored.size() + 1));
                stored.add(v);
            }
            return v;
        });
        when(verifications.findByPhoneNumberAndPurposeAndVerifiedAtIsNull(anyString(), anyString()))
                .thenAnswer(i -> stored.stream()
                        .filter(v -> v.getPhoneNumber().equals(i.getArgument(0))
                                && v.getPurpose().equals(i.getArgument(1))
                                && v.getVerifiedAt() == null)
                        .findFirst());
        when(verifications.findByPhoneNumberAndVerifiedAtIsNotNull(anyString()))
                .thenAnswer(i -> stored.stream()
                        .filter(v -> v.getPhoneNumber().equals(i.getArgument(0))
                                && v.getVerifiedAt() != null).toList());
        when(verifications
                .findByPhoneNumberAndPurposeAndAccessTokenHashIsNotNullAndAccessUsedAtIsNullOrderByIdDesc(
                        anyString(), anyString()))
                .thenAnswer(i -> stored.stream()
                        .filter(v -> v.getPhoneNumber().equals(i.getArgument(0))
                                && v.getPurpose().equals(i.getArgument(1))
                                && v.getAccessTokenHash() != null
                                && v.getAccessUsedAt() == null)
                        .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                        .toList());
        org.mockito.Mockito.doAnswer(i -> {
            stored.remove((PhoneVerification) i.getArgument(0));
            return null;
        }).when(verifications).delete(any());
    }

    /** The code as the customer would read it off their phone. */
    private String sentCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsService, org.mockito.Mockito.atLeastOnce())
                .trySend(anyString(), text.capture());
        Matcher m = Pattern.compile("\\b(\\d{6})\\b").matcher(text.getValue());
        assertThat(m.find()).as("the text should carry a six-digit code").isTrue();
        return m.group(1);
    }

    // --- requesting ---

    @Test
    @DisplayName("a request texts a six-digit code and says when it dies")
    void requestSendsACode() {
        PhoneVerificationService.Requested r =
                service.request("0712345678", "GENERIC", "1.2.3.4");

        assertThat(r.sent()).isTrue();
        assertThat(r.expiresAt()).isAfter(Instant.now());
        // Normalised, so a customer typing 07... is texted on 254...
        assertThat(r.message()).contains("254712345678");
        assertThat(sentCode()).hasSize(6);
    }

    @Test
    @DisplayName("the code is not stored in the clear")
    void codeIsHashed() {
        service.request("0712345678", "GENERIC", "1.2.3.4");
        String code = sentCode();

        // A dump taken inside the ten-minute window would otherwise hand over
        // every live code in the table.
        assertThat(stored.get(0).getCodeHash()).isNotEqualTo(code).hasSize(64);
    }

    @Test
    @DisplayName("asking twice replaces the first code rather than stacking a second")
    void secondRequestReplacesTheFirst() {
        service.request("0712345678", "GENERIC", "1.2.3.4");
        service.request("0712345678", "GENERIC", "1.2.3.4");

        // Two live codes means a customer holding two texts with no idea which
        // one the system will accept.
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("a number that is not a number is refused without a text")
    void rubbishNumberRefused() {
        PhoneVerificationService.Requested r = service.request("hello", "GENERIC", "1.2.3.4");

        assertThat(r.sent()).isFalse();
        verify(smsService, never()).trySend(anyString(), anyString());
    }

    @Test
    @DisplayName("a number that has had several codes is told to stop rather than texted again")
    void perNumberRateLimit() {
        when(verifications.countByPhoneNumberAndCreatedAtAfter(anyString(), any())).thenReturn(5L);

        PhoneVerificationService.Requested r =
                service.request("0712345678", "GENERIC", "1.2.3.4");

        assertThat(r.sent()).isFalse();
        // Says so rather than pretending: somebody who genuinely has not received
        // four texts needs to try something else, not keep tapping.
        assertThat(r.message()).contains("several codes");
        verify(smsService, never()).trySend(anyString(), anyString());
    }

    @Test
    @DisplayName("one address cannot walk a thousand numbers")
    void perAddressRateLimit() {
        when(verifications.countByRequestedIpAndCreatedAtAfter(anyString(), any())).thenReturn(30L);

        PhoneVerificationService.Requested r =
                service.request("0712345678", "GENERIC", "1.2.3.4");

        // Limiting only the number would leave this wide open, and every one of
        // those texts is money.
        assertThat(r.sent()).isFalse();
        verify(smsService, never()).trySend(anyString(), anyString());
    }

    // --- confirming ---

    @Test
    @DisplayName("the right code confirms the number")
    void rightCodeWorks() {
        service.request("0712345678", "GENERIC", "1.2.3.4");
        String code = sentCode();

        PhoneVerificationService.Checked c = service.verify("0712345678", "GENERIC", code);

        assertThat(c.verified()).isTrue();
        assertThat(stored.get(0).getVerifiedAt()).isNotNull();
        assertThat(service.isVerified("0712345678")).isTrue();
    }

    @Test
    @DisplayName("a wrong code counts the attempt and says how many are left")
    void wrongCodeCountsDown() {
        service.request("0712345678", "GENERIC", "1.2.3.4");

        PhoneVerificationService.Checked c = service.verify("0712345678", "GENERIC", "000000");

        assertThat(c.verified()).isFalse();
        assertThat(c.message()).contains("4 tries left");
        assertThat(stored.get(0).getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("five wrong tries kill the code, and the right one then fails too")
    void guessingIsBounded() {
        service.request("0712345678", "GENERIC", "1.2.3.4");
        String code = sentCode();

        for (int i = 0; i < 5; i++) {
            service.verify("0712345678", "GENERIC", "000000");
        }
        // The bound is the whole reason six digits is enough. Without it a
        // million guesses is a valid strategy.
        PhoneVerificationService.Checked c = service.verify("0712345678", "GENERIC", code);

        assertThat(c.verified()).isFalse();
        assertThat(c.message()).contains("Too many wrong tries");
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("an expired code is refused and cleared away")
    void expiredCodeRefused() {
        service.request("0712345678", "GENERIC", "1.2.3.4");
        String code = sentCode();
        stored.get(0).setExpiresAt(Instant.now().minusSeconds(1));

        PhoneVerificationService.Checked c = service.verify("0712345678", "GENERIC", code);

        assertThat(c.verified()).isFalse();
        assertThat(c.message()).contains("expired");
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("a number with no live challenge gets the same answer as a wrong code")
    void noChallengeLooksLikeAWrongCode() {
        PhoneVerificationService.Checked c = service.verify("0712345678", "GENERIC", "123456");

        // Telling these apart tells somebody guessing which numbers have live
        // challenges, which is the one piece of information worth having.
        assertThat(c.verified()).isFalse();
        assertThat(c.message()).isEqualTo("That code is not right. Ask for a new one.");
    }

    @Test
    @DisplayName("a code issued for one purpose cannot be spent on another")
    void purposeIsPartOfTheCode() {
        service.request("0712345678", "SIGNUP", "1.2.3.4");
        String code = sentCode();

        assertThat(service.verify("0712345678", "PASSWORD_RESET", code).verified()).isFalse();
        assertThat(service.verify("0712345678", "SIGNUP", code).verified()).isTrue();
    }

    @Test
    @DisplayName("the stored hash is salted with the number and the purpose")
    void hashIsSaltedByNumber() throws Exception {
        service.request("0712345678", "SIGNUP", "1.2.3.4");
        String code = sentCode();

        // Pinned to the formula rather than compared against a second request:
        // two requests produce two random codes, so their hashes differ whether
        // or not there is any salt -- which is exactly the test that passes while
        // the salt is missing.
        //
        // Unsalted, a precomputed table of a million SHA-256 digests reads every
        // live code in this table at once.
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        String salted = java.util.HexFormat.of().formatHex(digest.digest(
                ("254712345678|SIGNUP|" + code)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(stored.get(0).getCodeHash()).isEqualTo(salted);

        java.security.MessageDigest bare = java.security.MessageDigest.getInstance("SHA-256");
        String unsalted = java.util.HexFormat.of().formatHex(bare.digest(
                code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(stored.get(0).getCodeHash()).isNotEqualTo(unsalted);
    }

    @Test
    @DisplayName("an unverified number reads as unverified")
    void unverifiedIsUnverified() {
        assertThat(service.isVerified("0712345678")).isFalse();
        service.request("0712345678", "GENERIC", "1.2.3.4");
        assertThat(service.isVerified("0712345678")).isFalse();
    }

    // --- proving ownership ---

    /** Requests a code, reads it off the text, and enters it. */
    private String prove(String phone, String purpose) {
        service.request(phone, purpose, "1.2.3.4");
        PhoneVerificationService.Checked c = service.verify(phone, purpose, sentCode());
        assertThat(c.verified()).isTrue();
        return c.token();
    }

    @Test
    @DisplayName("entering the right code hands back a token, and a wrong one never does")
    void tokenOnlyOnSuccess() {
        service.request("0712345678", "CREDIT", "1.2.3.4");
        String code = sentCode();

        assertThat(service.verify("0712345678", "CREDIT", "000000").token()).isNull();
        assertThat(service.verify("0712345678", "CREDIT", code).token()).isNotBlank();
    }

    @Test
    @DisplayName("the token is spent by the first use")
    void tokenIsSingleUse() {
        String token = prove("0712345678", "CREDIT");

        assertThat(service.consume("0712345678", "CREDIT", token)).isTrue();
        // Otherwise one code drains an account by repeating the same call.
        assertThat(service.consume("0712345678", "CREDIT", token)).isFalse();
    }

    @Test
    @DisplayName("a token proves one number, not any number")
    void tokenIsBoundToTheNumber() {
        String token = prove("0712345678", "CREDIT");

        assertThat(service.consume("0798765432", "CREDIT", token)).isFalse();
    }

    @Test
    @DisplayName("a token proves one purpose, not any purpose")
    void tokenIsBoundToThePurpose() {
        String token = prove("0712345678", "LOYALTY");

        // A code asked for to look at a points balance must not authorise an
        // advance, or the narrowest thing a customer agreed to becomes the widest.
        assertThat(service.consume("0712345678", "CREDIT", token)).isFalse();
    }

    @Test
    @DisplayName("looking does not spend the proof, so one code covers a visit")
    void holdsProofDoesNotSpend() {
        String token = prove("0712345678", "CREDIT");

        assertThat(service.holdsProof("0712345678", "CREDIT", token)).isTrue();
        assertThat(service.holdsProof("0712345678", "CREDIT", token)).isTrue();
        // Still good for the action the customer came to do.
        assertThat(service.consume("0712345678", "CREDIT", token)).isTrue();
        assertThat(service.holdsProof("0712345678", "CREDIT", token)).isFalse();
    }

    @Test
    @DisplayName("a made-up token proves nothing")
    void inventedTokenIsRefused() {
        prove("0712345678", "CREDIT");

        assertThat(service.consume("0712345678", "CREDIT", "deadbeefdeadbeefdeadbeefdeadbeef"))
                .isFalse();
        assertThat(service.consume("0712345678", "CREDIT", null)).isFalse();
        assertThat(service.consume("0712345678", "CREDIT", "  ")).isFalse();
    }

    @Test
    @DisplayName("an expired token is refused even though the number stays proved")
    void expiredTokenIsRefused() {
        String token = prove("0712345678", "CREDIT");
        stored.forEach(v -> v.setAccessExpiresAt(Instant.now().minusSeconds(1)));

        assertThat(service.consume("0712345678", "CREDIT", token)).isFalse();
        // The verified row is the permanent record that the number was proved,
        // and it is still there -- which is exactly why it cannot be the thing
        // that authorises a payout.
        assertThat(service.isVerified("0712345678")).isTrue();
    }

    @Test
    @DisplayName("having once been verified is not permission to do anything")
    void beingVerifiedIsNotAuthorisation() {
        prove("0712345678", "CREDIT");
        stored.forEach(v -> v.setAccessUsedAt(Instant.now()));

        // The hole this closed: isVerified() means "ever proved" and stays true
        // forever, so a number confirmed at signup would have authorised an
        // advance months later to whoever typed it in.
        assertThat(service.isVerified("0712345678")).isTrue();
        assertThat(service.consume("0712345678", "CREDIT", "anything")).isFalse();
    }
}
