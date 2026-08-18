package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.RadiusClient;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Who gets online, and on what terms.
 *
 * <p>The same decision the MikroTik integration makes today by writing users
 * onto the router in advance — except made at the moment of login, once, for
 * any vendor's hardware.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RadiusAuthServiceTest {

    @Mock
    private VoucherRepository vouchers;

    @Mock
    private SubscriberRepository subscribers;

    @InjectMocks
    private RadiusAuthService auth;

    private RadiusClient mikrotik;

    @BeforeEach
    void setUp() {
        mikrotik = RadiusClient.builder().name("Site 1").address("10.0.0.1")
                .sharedSecret("s").vendor(RadiusClient.Vendor.MIKROTIK).build();
        when(vouchers.findByCode(anyString())).thenReturn(Optional.empty());
        when(subscribers.findByPppoeUsername(anyString())).thenReturn(Optional.empty());
    }

    /** A check that says yes to one specific password and no to everything else. */
    private static RadiusAuthService.PasswordCheck offering(String password) {
        return new RadiusAuthService.PasswordCheck() {
            @Override
            public boolean matches(String knownPassword) {
                return password.equals(knownPassword);
            }

            @Override
            public boolean unsupported() {
                return false;
            }
        };
    }

    private static RadiusAuthService.PasswordCheck unsupportedScheme() {
        return new RadiusAuthService.PasswordCheck() {
            @Override
            public boolean matches(String knownPassword) {
                return false;
            }

            @Override
            public boolean unsupported() {
                return true;
            }
        };
    }

    private static Plan plan(String bandwidth, int minutes) {
        Plan plan = new Plan();
        plan.setName("Test");
        plan.setDurationMinutes(minutes);
        plan.setBandwidth(bandwidth);
        return plan;
    }

    private static Voucher voucher(String code, Plan plan, long usedSeconds, Voucher.Status status) {
        return Voucher.builder().id(1L).code(code).plan(plan)
                .usedSeconds(usedSeconds).status(status).build();
    }

    private static Long attributeValue(RadiusAuthService.Decision decision, int type) {
        return decision.attributes().stream()
                .filter(a -> a.type() == type)
                .map(a -> Integer.toUnsignedLong(ByteBuffer.wrap(a.value()).getInt()))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("A pass with time left is accepted, and told exactly how long it has")
    void acceptsVoucherWithTimeLeft() {
        Voucher v = voucher("ABC123", plan("5M/10M", 60), 600, Voucher.Status.ACTIVE);
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));

        var decision = auth.authorise("ABC123", offering("ABC123"), mikrotik, 300);

        assertThat(decision.accept()).isTrue();
        assertThat(decision.voucherId()).isEqualTo(1L);
        // 60 minutes bought, 10 used: this is what makes the pass actually end
        // on hardware we have no other way to reach.
        assertThat(attributeValue(decision, RadiusPacket.SESSION_TIMEOUT)).isEqualTo(3000);
        assertThat(attributeValue(decision, RadiusPacket.ACCT_INTERIM_INTERVAL)).isEqualTo(300);
    }

    @Test
    @DisplayName("A used-up pass is refused even though its status still says active")
    void refusesExhaustedVoucher() {
        // Status lags reality between polls; the arithmetic does not.
        Voucher v = voucher("ABC123", plan("5M/10M", 60), 3_600, Voucher.Status.ACTIVE);
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));

        var decision = auth.authorise("ABC123", offering("ABC123"), mikrotik, 300);

        assertThat(decision.accept()).isFalse();
        assertThat(decision.message()).contains("used up");
    }

    @Test
    @DisplayName("A pass past its wall-clock deadline is refused even with seconds unspent")
    void refusesPastDeadline() {
        Voucher v = voucher("ABC123", plan("5M/10M", 60), 0, Voucher.Status.ACTIVE);
        v.setExpiresAt(Instant.now().minusSeconds(60));
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));

        var decision = auth.authorise("ABC123", offering("ABC123"), mikrotik, 300);

        assertThat(decision.accept()).isFalse();
        assertThat(decision.message()).contains("expired");
    }

    @Test
    @DisplayName("An unknown code and a wrong password are refused in identical words")
    void noUserEnumeration() {
        Voucher v = voucher("ABC123", plan("5M/10M", 60), 0, Voucher.Status.ACTIVE);
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));

        var wrongPassword = auth.authorise("ABC123", offering("nope"), mikrotik, 300);
        var unknownUser = auth.authorise("NOSUCHCODE", offering("nope"), mikrotik, 300);

        assertThat(wrongPassword.accept()).isFalse();
        assertThat(unknownUser.accept()).isFalse();
        // Identical, deliberately: a different answer for a code that exists is
        // how a voucher system gets brute-forced one guess at a time.
        assertThat(wrongPassword.message()).isEqualTo(unknownUser.message());
    }

    @Test
    @DisplayName("MikroTik gets its own rate string; everyone else gets numbers")
    void vendorRateLimits() {
        Voucher v = voucher("ABC123", plan("5M/10M", 60), 0, Voucher.Status.ACTIVE);
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));

        var forMikrotik = auth.authorise("ABC123", offering("ABC123"), mikrotik, 300);
        RadiusClient ubiquiti = RadiusClient.builder().name("AP").address("10.0.0.2")
                .sharedSecret("s").vendor(RadiusClient.Vendor.UBIQUITI).build();
        var forUbiquiti = auth.authorise("ABC123", offering("ABC123"), ubiquiti, 300);

        assertThat(vendorPayload(forMikrotik)).contains("5M/10M");
        // WISPr's pair, in plain bits per second — which is what everything
        // that is not a MikroTik actually understands.
        assertThat(vendorPayload(forUbiquiti)).doesNotContain("5M/10M");
        assertThat(forUbiquiti.attributes().stream()
                .filter(a -> a.type() == RadiusPacket.VENDOR_SPECIFIC).count()).isEqualTo(2);
    }

    private static String vendorPayload(RadiusAuthService.Decision decision) {
        StringBuilder out = new StringBuilder();
        decision.attributes().stream()
                .filter(a -> a.type() == RadiusPacket.VENDOR_SPECIFIC)
                .forEach(a -> out.append(new String(a.value(), java.nio.charset.StandardCharsets.UTF_8)));
        return out.toString();
    }

    @Test
    @DisplayName("A monthly subscriber is never sent a session timeout")
    void subscriberHasNoSessionTimeout() {
        Subscriber sub = Subscriber.builder().id(9L).pppoeUsername("jane")
                .pppoePassword("hunter2").bandwidth("10M/20M")
                .status(Subscriber.Status.ACTIVE).build();
        when(subscribers.findByPppoeUsername("jane")).thenReturn(Optional.of(sub));

        var decision = auth.authorise("jane", offering("hunter2"), mikrotik, 300);

        assertThat(decision.accept()).isTrue();
        assertThat(decision.subscriberId()).isEqualTo(9L);
        // Their access ends on a date, not after a number of seconds. Sending
        // one would disconnect every PPPoE customer on a timer.
        assertThat(attributeValue(decision, RadiusPacket.SESSION_TIMEOUT)).isNull();
    }

    @Test
    @DisplayName("A suspended customer is told they are suspended, not that they mistyped")
    void suspendedSaysSo() {
        Subscriber sub = Subscriber.builder().id(9L).pppoeUsername("jane")
                .pppoePassword("hunter2").status(Subscriber.Status.SUSPENDED).build();
        when(subscribers.findByPppoeUsername("jane")).thenReturn(Optional.of(sub));

        var decision = auth.authorise("jane", offering("hunter2"), mikrotik, 300);

        assertThat(decision.accept()).isFalse();
        // The customer who rings up should be told the truth; sending them
        // round the houses over a password they typed correctly wastes
        // everybody's afternoon.
        assertThat(decision.message()).contains("suspended");
    }

    @Test
    @DisplayName("A login method we do not implement says so rather than blaming the password")
    void unsupportedSchemeIsNamed() {
        Voucher v = voucher("ABC123", plan("5M/10M", 60), 0, Voucher.Status.ACTIVE);
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));

        var decision = auth.authorise("ABC123", unsupportedScheme(), mikrotik, 300);

        assertThat(decision.accept()).isFalse();
        // The operator has a NAS set to EAP or MS-CHAP and needs to know that,
        // not to spend an evening resetting a customer's password.
        assertThat(decision.message()).contains("PAP or CHAP");
    }

    @Test
    @DisplayName("An empty username is refused without touching the database")
    void blankUsername() {
        assertThat(auth.authorise("", offering("x"), mikrotik, 300).accept()).isFalse();
        assertThat(auth.authorise(null, offering("x"), mikrotik, 300).accept()).isFalse();
    }
}
