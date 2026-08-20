package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.domain.AcsSettings;
import com.spalimited.hotspotbilling.repository.AcsSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Who the ACS will talk to.
 *
 * <p>The reason this exists is that it used to talk to anybody: a forged Inform
 * filed a device, and asking for orders handed over whatever the operator had
 * queued for that serial and marked it sent, so the real box never got it. A
 * serial number is printed on the case, so it was never the secret.
 *
 * <p>The test that matters most is the first one. Shut-when-unconfigured is the
 * whole safety property — an operator who upgrades and sets nothing must end up
 * with an ACS that refuses devices, not one that still accepts strangers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AcsAuthTest {

    @Mock
    private AcsSettingsRepository repository;

    private final PasswordEncoder encoder =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private AcsAuth auth;
    private AcsSettings row;

    @BeforeEach
    void setUp() {
        row = AcsSettings.builder().id(1L).allowUnknown(true).build();
        when(repository.findById(1L)).thenAnswer(i -> Optional.of(row));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        auth = new AcsAuth(repository, encoder);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("with no credentials set, nothing is allowed in")
    void shutUntilConfigured() {
        assertThat(auth.configured()).isFalse();
        // Not even a well-formed guess, and not the empty header either.
        assertThat(auth.permits(basic("admin", "admin"))).isFalse();
        assertThat(auth.permits(null)).isFalse();
    }

    @Test
    @DisplayName("the configured pair is accepted")
    void correctPairIsAccepted() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        assertThat(auth.configured()).isTrue();
        assertThat(auth.permits(basic("cpe", "a-long-enough-password"))).isTrue();
    }

    @Test
    @DisplayName("a wrong password is refused, and so is a wrong username")
    void wrongCredentialsRefused() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        assertThat(auth.permits(basic("cpe", "not-the-password"))).isFalse();
        assertThat(auth.permits(basic("someone-else", "a-long-enough-password"))).isFalse();
    }

    @Test
    @DisplayName("a malformed header is refused rather than throwing")
    void malformedHeaderRefused() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        // A device with a broken stack, or somebody poking at it. Either way an
        // exception here would be a 500 that says the endpoint exists and is
        // interesting.
        assertThat(auth.permits("Basic not-base64!!")).isFalse();
        assertThat(auth.permits("Basic " + Base64.getEncoder()
                .encodeToString("no-colon".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(auth.permits("Bearer sometoken")).isFalse();
        assertThat(auth.permits("")).isFalse();
    }

    @Test
    @DisplayName("the scheme is matched without regard to case, as the RFC says")
    void schemeIsCaseInsensitive() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        assertThat(auth.permits(basic("cpe", "a-long-enough-password")
                .replace("Basic", "basic"))).isTrue();
    }

    @Test
    @DisplayName("the password is stored encoded, never as itself")
    void passwordIsHashed() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        assertThat(row.getPasswordHash()).isNotNull();
        assertThat(row.getPasswordHash()).doesNotContain("a-long-enough-password");
    }

    @Test
    @DisplayName("a short password is refused, because every device shares it")
    void shortPasswordRefused() {
        assertThatThrownBy(() -> auth.save("cpe", "short", true, "grace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 characters");
    }

    @Test
    @DisplayName("saving with a blank password leaves the old one working")
    void blankPasswordKeepsTheOldOne() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        auth.save("cpe", "  ", false, "grace");

        // Otherwise editing the username on a settings page silently locks every
        // device in the field out.
        assertThat(auth.permits(basic("cpe", "a-long-enough-password"))).isTrue();
        assertThat(row.isAllowUnknown()).isFalse();
    }

    @Test
    @DisplayName("who changed it and when are recorded")
    void changeIsAttributed() {
        auth.save("cpe", "a-long-enough-password", true, "grace");

        assertThat(row.getUpdatedBy()).isEqualTo("grace");
        assertThat(row.getUpdatedAt()).isNotNull();
    }
}
