package com.spalimited.hotspotbilling.config;

import com.spalimited.hotspotbilling.domain.MessagingSettings;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The door on the WhatsApp webhook.
 *
 * <p>Everything the two bots do is authorised by the sender's phone number, so
 * this check is the only thing standing between a stranger with the URL and
 * either a customer's access code or the whole technician job queue.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WhatsappSignatureGuardTest {

    private static final String SECRET = "3f8a1c9e5b2d47f0a6c8e1b3d5f70921";
    private static final byte[] BODY =
            """
            {"entry":[{"changes":[{"value":{"messages":[
              {"type":"text","from":"254711000111","text":{"body":"jobs"}}]}}]}]}
            """.getBytes(StandardCharsets.UTF_8);

    @Mock private MessagingSettingsService messagingSettings;

    private WhatsappSignatureGuard guard;
    private MessagingSettings settings;

    @BeforeEach
    void setUp() {
        guard = new WhatsappSignatureGuard(messagingSettings);
        settings = MessagingSettings.builder().id(1L).whatsappEnabled(true)
                .whatsappAppSecret(SECRET).build();
        when(messagingSettings.settings()).thenReturn(settings);
    }

    /** What Meta would actually put on the wire for this body. */
    private static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("A genuine Meta delivery is let through")
    void acceptsAGenuineDelivery() {
        assertThatCode(() -> guard.assertFromMeta(BODY, sign(SECRET, BODY)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A payload nobody signed is refused")
    void rejectsAnUnsignedPayload() {
        assertThatThrownBy(() -> guard.assertFromMeta(BODY, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("A payload signed with the wrong secret is refused")
    void rejectsTheWrongSecret() {
        assertThatThrownBy(() -> guard.assertFromMeta(BODY, sign("not-the-secret", BODY)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("A body altered after signing is refused")
    void rejectsATamperedBody() {
        String honest = sign(SECRET, BODY);
        // The attack this exists to stop: take a real delivery and change who
        // it claims to be from.
        byte[] tampered = new String(BODY, StandardCharsets.UTF_8)
                .replace("254711000111", "254799999999")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> guard.assertFromMeta(tampered, honest))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("A signature without the sha256= prefix is refused rather than half-read")
    void rejectsAMalformedHeader() {
        String raw = sign(SECRET, BODY).substring("sha256=".length());

        assertThatThrownBy(() -> guard.assertFromMeta(BODY, raw))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("With no app secret set it warns and lets through, rather than silently breaking setup")
    void staysOpenUntilConfigured() {
        settings.setWhatsappAppSecret(null);

        assertThatCode(() -> guard.assertFromMeta(BODY, null)).doesNotThrowAnyException();
        // ...and says so, so the state is visible rather than assumed safe.
        assertThat(guard.isEnforcing()).isFalse();
    }

    @Test
    @DisplayName("Once the secret is set, it is enforcing")
    void enforcesOnceConfigured() {
        assertThat(guard.isEnforcing()).isTrue();
    }
}
