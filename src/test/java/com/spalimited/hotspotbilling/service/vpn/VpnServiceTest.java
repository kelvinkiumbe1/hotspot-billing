package com.spalimited.hotspotbilling.service.vpn;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.VpnSettings;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.repository.VpnSettingsRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MikrotikService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The management tunnel.
 *
 * <p>The thing worth testing hardest is address allocation, because a duplicate
 * address on a WireGuard tunnel is not an error anybody sees -- it is two routers
 * both claiming to be 10.77.0.4, one of them silently unreachable, and the
 * symptom appears on whichever one the server happened to route to last.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VpnServiceTest {

    @Mock
    private VpnSettingsRepository settingsRepo;

    @Mock
    private RouterRepository routers;

    @Mock
    private MikrotikService mikrotikService;

    @Mock
    private AuditService audit;

    @InjectMocks
    private VpnService vpn;

    private VpnSettings cfg;
    private final List<Router> all = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cfg = VpnSettings.builder()
                .id(VpnSettings.SINGLETON_ID)
                .enabled(true)
                .serverPublicKey("SERVERKEYAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
                .endpoint("vpn.example.net:13231")
                .subnet("10.77.0.0/24")
                .serverAddress("10.77.0.1")
                .keepaliveSeconds(25)
                .interfaceName("zidi-vpn")
                .build();
        when(settingsRepo.findById(VpnSettings.SINGLETON_ID)).thenReturn(Optional.of(cfg));
        when(settingsRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        all.clear();
        when(routers.findAll()).thenReturn(all);
        when(routers.save(any())).thenAnswer(i -> i.getArgument(0));
        when(routers.findById(anyLong())).thenAnswer(i ->
                all.stream().filter(r -> i.getArgument(0).equals(r.getId())).findFirst());
    }

    private Router router(long id, String name, String vpnAddress) {
        Router r = new Router();
        r.setId(id);
        r.setName(name);
        r.setHost("10.90.0." + id);
        r.setEnabled(true);
        r.setVpnAddress(vpnAddress);
        all.add(r);
        return r;
    }

    // --- allocation ---

    @Test
    @DisplayName("the first address handed out skips the server's own")
    void firstAddressSkipsServer() {
        assertThat(vpn.nextFreeAddress()).isEqualTo("10.77.0.2");
    }

    @Test
    @DisplayName("an address already in use is never handed out twice")
    void noDuplicates() {
        router(1, "Main", "10.77.0.2");
        router(2, "Westlands", "10.77.0.3");

        // Two routers on one tunnel address is not an error anybody sees: it is
        // one of them silently unreachable, depending on which the server routed
        // to last.
        assertThat(vpn.nextFreeAddress()).isEqualTo("10.77.0.4");
    }

    @Test
    @DisplayName("a gap left by a deleted router is reused")
    void gapsAreFilled() {
        router(1, "Main", "10.77.0.2");
        router(2, "Westlands", "10.77.0.4");

        assertThat(vpn.nextFreeAddress()).isEqualTo("10.77.0.3");
    }

    @Test
    @DisplayName("a full subnet says so instead of handing out something invalid")
    void fullSubnetRefuses() {
        cfg.setSubnet("10.77.0.0/30");
        cfg.setServerAddress("10.77.0.1");
        router(1, "a", "10.77.0.2");

        // A /30 holds .1 .2 .3 minus broadcast: server plus one router fills it.
        assertThatThrownBy(vpn::nextFreeAddress)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full");
    }

    // --- configuring a router ---

    @Test
    @DisplayName("configuring stores the address and the key the router reported")
    void configureStoresWhatTheRouterSaid() {
        Router r = router(1, "Westlands", null);
        when(mikrotikService.setupWireguard(any(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn("ROUTERKEY123=");

        VpnService.Configured result = vpn.configure(1L, "grace");

        assertThat(result.ok()).isTrue();
        assertThat(result.address()).isEqualTo("10.77.0.2");
        assertThat(r.getVpnAddress()).isEqualTo("10.77.0.2");
        // Read off the box, never generated here -- the private half stays on the
        // router and never enters this system.
        assertThat(r.getVpnPublicKey()).isEqualTo("ROUTERKEY123=");
        assertThat(r.getVpnConfiguredAt()).isNotNull();
    }

    @Test
    @DisplayName("re-configuring keeps the address the router already has")
    void reconfigureKeepsTheAddress() {
        Router r = router(1, "Westlands", "10.77.0.9");
        when(mikrotikService.setupWireguard(any(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyInt())).thenReturn("KEY=");

        VpnService.Configured result = vpn.configure(1L, "grace");

        // Moving it would leave the server's peer list pointing at an address the
        // router no longer answers on, which reads as the tunnel having broken.
        assertThat(result.address()).isEqualTo("10.77.0.9");
        assertThat(r.getVpnAddress()).isEqualTo("10.77.0.9");
    }

    @Test
    @DisplayName("an unreachable router records why and claims nothing")
    void unreachableRouterIsHonest() {
        Router r = router(1, "Westlands", null);
        when(mikrotikService.setupWireguard(any(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("connection timed out"));

        VpnService.Configured result = vpn.configure(1L, "grace");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("connection timed out");
        assertThat(r.getVpnAddress()).isNull();
        assertThat(r.getVpnPublicKey()).isNull();
        assertThat(r.getVpnLastError()).contains("connection timed out");
    }

    @Test
    @DisplayName("an unconfigured tunnel refuses before touching any router")
    void unusableSettingsRefuseEarly() {
        cfg.setServerPublicKey(null);
        router(1, "Westlands", null);

        VpnService.Configured result = vpn.configure(1L, "grace");

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("server public key");
        verify(mikrotikService, never()).setupWireguard(any(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString(), anyInt());
    }

    // --- the peer line the operator has to paste ---

    @Test
    @DisplayName("a peer is pinned to a single address, not to the whole tunnel")
    void peerIsPinnedToOneAddress() {
        Router r = router(1, "Westlands", "10.77.0.5");
        r.setVpnPublicKey("ROUTERKEY=");

        String stanza = vpn.peerStanza(r, cfg);

        assertThat(stanza).contains("PublicKey = ROUTERKEY=");
        // /32, not the subnet. A peer allowed the whole range could send traffic
        // claiming to be any other router on the tunnel -- on a network of
        // franchise partners that is the whole point of having a tunnel.
        assertThat(stanza).contains("AllowedIPs = 10.77.0.5/32");
        assertThat(stanza).doesNotContain("/24");
        assertThat(stanza).contains("Westlands");
    }

    @Test
    @DisplayName("a router with no key yet produces no peer line")
    void noKeyNoStanza() {
        Router r = router(1, "Westlands", "10.77.0.5");

        assertThat(vpn.peerStanza(r, cfg)).isNull();
    }

    @Test
    @DisplayName("the overview flags a router whose peer line was never added")
    void awaitingPeerIsVisible() {
        Router configured = router(1, "Westlands", "10.77.0.2");
        configured.setVpnPublicKey("KEY1=");
        Router working = router(2, "Kasarani", "10.77.0.3");
        working.setVpnPublicKey("KEY2=");
        working.setVpnLastOkAt(Instant.now());

        List<Map<String, Object>> rows = vpn.overview();

        // Set up on the router but never seen working is exactly what a forgotten
        // peer line looks like from this end, and it is the commonest mistake.
        assertThat(rows.get(0).get("awaitingPeer")).isEqualTo(true);
        assertThat(rows.get(1).get("awaitingPeer")).isEqualTo(false);
    }

    // --- checking the tunnel specifically ---

    @Test
    @DisplayName("a working tunnel is recorded as having worked")
    void checkRecordsSuccess() {
        Router r = router(1, "Westlands", "10.77.0.2");
        when(mikrotikService.reachableAt(r, "10.77.0.2")).thenReturn(true);

        Map<String, Object> result = vpn.check(1L);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(r.getVpnLastOkAt()).isNotNull();
        assertThat(r.getVpnLastError()).isNull();
    }

    @Test
    @DisplayName("a dead tunnel names the likeliest cause rather than shrugging")
    void checkExplainsFailure() {
        Router r = router(1, "Westlands", "10.77.0.2");
        when(mikrotikService.reachableAt(r, "10.77.0.2")).thenReturn(false);

        Map<String, Object> result = vpn.check(1L);

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("peer line");
        assertThat(r.getVpnLastOkAt()).isNull();
        assertThat(r.getVpnLastError()).isNotNull();
    }

    @Test
    @DisplayName("checking a router with no tunnel says so rather than failing")
    void checkWithoutAnAddress() {
        router(1, "Westlands", null);

        Map<String, Object> result = vpn.check(1L);

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("message")).contains("no tunnel address");
    }

    // --- the commands ---

    @Test
    @DisplayName("the pasteable script carries no private key and splits the endpoint")
    void scriptShape() {
        router(1, "Westlands", "10.77.0.7");

        List<String> commands = vpn.script(1L);
        String all = String.join("\n", commands);

        assertThat(all).contains("/interface/wireguard/add name=zidi-vpn");
        assertThat(all).contains("address=10.77.0.7/24");
        assertThat(all).contains("endpoint-address=vpn.example.net");
        assertThat(all).contains("endpoint-port=13231");
        assertThat(all).contains("persistent-keepalive=25s");
        // The router makes its own private key. Nothing here should ever carry one.
        assertThat(all.toLowerCase()).doesNotContain("private-key");
    }

    @Test
    @DisplayName("a bad subnet is refused when it is saved, not when a router is configured")
    void badSubnetRefusedAtSave() {
        assertThatThrownBy(() -> vpn.save(VpnSettings.builder()
                .enabled(true).subnet("not-a-subnet").keepaliveSeconds(25).build(), "grace"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
