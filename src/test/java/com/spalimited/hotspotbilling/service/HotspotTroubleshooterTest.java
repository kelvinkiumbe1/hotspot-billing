package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.Voucher;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Why one customer cannot get online.
 *
 * <p>The value is entirely in giving the RIGHT reason. A tool that answers "the
 * code is not on the router" when the truth is "the router is down" sends
 * somebody to re-push a voucher that was never the problem, and they trust it
 * less next time. So most of these tests are about the tool refusing to guess.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HotspotTroubleshooterTest {

    @Mock
    private VoucherRepository vouchers;

    @Mock
    private MikrotikService mikrotikService;

    @InjectMocks
    private HotspotTroubleshooter troubleshooter;

    private Router router;
    private Plan plan;

    @BeforeEach
    void setUp() {
        router = new Router();
        router.setId(1L);
        router.setName("Main Router");
        router.setEnabled(true);
        when(mikrotikService.routerFor(any())).thenReturn(router);
        when(mikrotikService.manageable(router)).thenReturn(true);

        plan = new Plan();
        plan.setName("1 hour");
        plan.setDurationMinutes(60);
    }

    private Voucher voucher(Voucher.Status status) {
        Voucher v = Voucher.builder()
                .id(1L).code("ABC123").plan(plan).status(status)
                .createdAt(Instant.now().minusSeconds(600))
                .expiresAt(Instant.now().plusSeconds(3600))
                .usedBytes(0).build();
        when(vouchers.findByCode("ABC123")).thenReturn(Optional.of(v));
        return v;
    }

    private void routerSays(Map<String, Object> state) {
        when(mikrotikService.hotspotUserState(any(), anyString(), any())).thenReturn(state);
    }

    private static Map<String, Object> healthy() {
        Map<String, Object> m = new HashMap<>();
        m.put("userExists", true);
        m.put("profileExists", true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checks(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("checks");
    }

    private static String verdictFor(Map<String, Object> result, String name) {
        return checks(result).stream()
                .filter(c -> name.equals(c.get("name")))
                .map(c -> String.valueOf(c.get("verdict")))
                .findFirst().orElse("absent");
    }

    // --- the code itself ---

    @Test
    @DisplayName("an unknown code stops there rather than blaming the router")
    void unknownCodeStopsEarly() {
        when(vouchers.findByCode("NOPE")).thenReturn(Optional.empty());

        Map<String, Object> result = troubleshooter.diagnose("NOPE", null);

        assertThat(result.get("summary")).asString().contains("No pass in the system");
        // A typo is the commonest cause by a wide margin. Touching the router
        // after this would be work for nothing and a slower answer.
        verify(mikrotikService, never()).hotspotUserState(any(), anyString(), any());
        assertThat(checks(result)).hasSize(1);
    }

    @Test
    @DisplayName("a blank code is refused without a lookup")
    void blankCode() {
        Map<String, Object> result = troubleshooter.diagnose("   ", null);

        assertThat(result.get("summary")).asString().contains("No code was given");
    }

    @Test
    @DisplayName("an expired pass is the answer, and it is given before the router is touched")
    void expiredIsTheAnswer() {
        Voucher v = voucher(Voucher.Status.EXPIRED);
        routerSays(healthy());

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        assertThat(result.get("summary")).asString().contains("marked expired");
        assertThat(verdictFor(result, "The pass is still valid")).isEqualTo("PROBLEM");
    }

    @Test
    @DisplayName("a pass that has never been used is fine, not a problem")
    void unusedIsNotAProblem() {
        voucher(Voucher.Status.UNUSED);
        routerSays(healthy());

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        // What every customer holds before their first connection. Calling it a
        // problem would make the tool cry wolf on the commonest healthy state.
        assertThat(verdictFor(result, "The pass is still valid")).isEqualTo("OK");
        assertThat(result.get("problems")).isEqualTo(0L);
    }

    @Test
    @DisplayName("time run out is reported as time, not as data")
    void timeRunOut() {
        Voucher v = voucher(Voucher.Status.ACTIVE);
        v.setExpiresAt(Instant.now().minusSeconds(60));
        routerSays(healthy());

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        assertThat(verdictFor(result, "It has time left")).isEqualTo("PROBLEM");
        assertThat(result.get("summary")).asString().contains("Ran out");
    }

    @Test
    @DisplayName("data run out is reported with the numbers")
    void dataRunOut() {
        Voucher v = voucher(Voucher.Status.ACTIVE);
        plan.setDataLimitMb(500);
        v.setUsedBytes(600L * 1024 * 1024);
        routerSays(healthy());

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        assertThat(verdictFor(result, "It has data left")).isEqualTo("PROBLEM");
        assertThat(result.get("summary")).asString().contains("600MB of 500MB");
    }

    // --- the router ---

    @Test
    @DisplayName("an unreachable router is not reported as a missing voucher")
    void unreachableRouterDoesNotBlameTheVoucher() {
        voucher(Voucher.Status.ACTIVE);
        when(mikrotikService.hotspotUserState(any(), anyString(), any()))
                .thenThrow(new IllegalStateException("connection timed out"));

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        // The whole point. Saying "the code is not on the router" here would send
        // somebody re-pushing a voucher that was never the problem.
        assertThat(verdictFor(result, "The router can be reached")).isEqualTo("PROBLEM");
        assertThat(verdictFor(result, "The code is on the router")).isEqualTo("absent");
        assertThat(result.get("summary")).asString().contains("connection timed out");
    }

    @Test
    @DisplayName("a router switched off in settings reads as unknown, not as broken")
    void switchedOffIsUnknown() {
        voucher(Voucher.Status.ACTIVE);
        when(mikrotikService.manageable(router)).thenReturn(false);

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        // A deliberate setting is not a fault. Reporting it as one sends somebody
        // debugging hardware that is fine.
        assertThat(verdictFor(result, "The router can be reached")).isEqualTo("UNKNOWN");
        assertThat(result.get("problems")).isEqualTo(0L);
        assertThat(result.get("summary")).asString().contains("could not be reached");
    }

    @Test
    @DisplayName("a code missing from the router is named as exactly that")
    void missingOnRouter() {
        voucher(Voucher.Status.ACTIVE);
        Map<String, Object> state = healthy();
        state.put("userExists", false);
        routerSays(state);

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        assertThat(verdictFor(result, "The code is on the router")).isEqualTo("PROBLEM");
        // This is the state a pass sold while the router was down leaves behind,
        // and the fix says so.
        assertThat(checks(result).stream()
                .filter(c -> "The code is on the router".equals(c.get("name")))
                .findFirst().orElseThrow().get("fix"))
                .asString().contains("unreachable");
    }

    // --- who is on it ---

    @Test
    @DisplayName("in use by another device is a warning, not a failure")
    void inUseElsewhere() {
        voucher(Voucher.Status.ACTIVE);
        Map<String, Object> state = healthy();
        state.put("activeMac", "AA:BB:CC:DD:EE:FF");
        routerSays(state);

        Map<String, Object> result = troubleshooter.diagnose("ABC123", "11:22:33:44:55:66");

        // Nothing is broken -- the pass works, somebody else is on it. Calling
        // that a failure would have people reissuing perfectly good codes.
        assertThat(verdictFor(result, "Who is using it")).isEqualTo("WARN");
        assertThat(result.get("problems")).isEqualTo(0L);
    }

    @Test
    @DisplayName("the customer's own session is not reported as somebody else")
    void ownSessionIsFine() {
        voucher(Voucher.Status.ACTIVE);
        Map<String, Object> state = healthy();
        state.put("activeMac", "AA:BB:CC:DD:EE:FF");
        routerSays(state);

        Map<String, Object> result = troubleshooter.diagnose("ABC123", "aa:bb:cc:dd:ee:ff");

        // Case-insensitive: RouterOS upper-cases MACs and a browser does not.
        assertThat(verdictFor(result, "Who is using it")).isEqualTo("OK");
    }

    @Test
    @DisplayName("a device tied to another pass is called out, because nothing else explains it")
    void macBinding() {
        voucher(Voucher.Status.ACTIVE);
        Map<String, Object> state = healthy();
        state.put("macBoundTo", "XYZ789");
        routerSays(state);

        Map<String, Object> result = troubleshooter.diagnose("ABC123", "11:22:33:44:55:66");

        assertThat(verdictFor(result, "This device is not tied to another pass")).isEqualTo("PROBLEM");
        assertThat(result.get("summary")).asString().contains("XYZ789");
    }

    @Test
    @DisplayName("a missing plan profile is named, since it looks like a wrong password")
    void missingProfile() {
        voucher(Voucher.Status.ACTIVE);
        Map<String, Object> state = healthy();
        state.put("profileExists", false);
        routerSays(state);

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        assertThat(verdictFor(result, "The plan's profile exists")).isEqualTo("PROBLEM");
        assertThat(result.get("summary")).asString().contains("1 hour");
    }

    // --- the awkward one ---

    @Test
    @DisplayName("everything healthy says so, and points at the device rather than shrugging")
    void allClear() {
        voucher(Voucher.Status.ACTIVE);
        routerSays(healthy());

        Map<String, Object> result = troubleshooter.diagnose("ABC123", null);

        assertThat(result.get("problems")).isEqualTo(0L);
        // The genuinely awkward outcome. Naming where to look next is the
        // difference between a useful tool and one people stop opening.
        assertThat(result.get("summary")).asString().contains("between their device");
    }
}
