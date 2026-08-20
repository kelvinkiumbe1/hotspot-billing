package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.IpAssignment;
import com.spalimited.hotspotbilling.domain.IpSubnet;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.IpAssignmentRepository;
import com.spalimited.hotspotbilling.repository.IpSubnetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two kinds of customer, one set of verbs.
 *
 * <p>The reason this class exists is a revenue hole rather than a feature gap: a
 * static customer has no PPP secret, so before this every suspension quietly did
 * nothing to them and a non-payer kept working. Most of these tests are therefore
 * about a static customer being reachable by every operation a PPPoE one is.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriberProvisioningServiceTest {

    @Mock
    private MikrotikService mikrotikService;

    @Mock
    private IpAssignmentRepository assignments;

    @Mock
    private IpSubnetRepository subnets;

    @InjectMocks
    private SubscriberProvisioningService provisioning;

    private IpSubnet subnet;

    @BeforeEach
    void setUp() {
        subnet = IpSubnet.builder()
                .id(5L).name("Static customers").cidr("41.90.64.0/26")
                .gateway("41.90.64.1").interfaceName("bridge-static").vlanId(120)
                .purpose(IpSubnet.Purpose.STATIC).build();
        when(subnets.findById(5L)).thenReturn(Optional.of(subnet));
        when(subnets.findAll()).thenReturn(List.of(subnet));
        when(assignments.findBySubscriberId(anyLong())).thenReturn(List.of(
                IpAssignment.builder().id(1L).subnetId(5L).subscriberId(7L)
                        .address("41.90.64.12").build()));
    }

    private Subscriber staticCustomer() {
        return Subscriber.builder()
                .id(7L).fullName("Acme Ltd").pppoeUsername("acme").pppoePassword("x")
                .bandwidth("20M/20M").routerId(1L)
                .connectionType(Subscriber.ConnectionType.STATIC)
                .staticIp("41.90.64.12").macAddress("aa:bb:cc:dd:ee:ff")
                .build();
    }

    private Subscriber pppoeCustomer() {
        return Subscriber.builder()
                .id(8L).fullName("Mary").pppoeUsername("mary").pppoePassword("x")
                .bandwidth("10M/10M").routerId(1L)
                .connectionType(Subscriber.ConnectionType.PPPOE)
                .build();
    }

    // --- the dispatch ---

    @Test
    @DisplayName("a PPPoE customer still goes down the PPPoE path")
    void pppoeUnchanged() {
        Subscriber mary = pppoeCustomer();

        provisioning.provision(mary);
        provisioning.setEnabled(mary, false);
        provisioning.setRate(mary, "2M/2M");
        provisioning.remove(mary);

        verify(mikrotikService).provisionPppoe(mary);
        verify(mikrotikService).setPppoeEnabled(mary, false);
        verify(mikrotikService).setPppoeRate(mary, "2M/2M");
        verify(mikrotikService).removePppoe(mary);
        verify(mikrotikService, never()).provisionStatic(any(), any());
    }

    @Test
    @DisplayName("a static customer can be cut off, which is the whole point")
    void staticCanBeSuspended() {
        Subscriber acme = staticCustomer();

        provisioning.setEnabled(acme, false);

        // Before this existed, suspension called setPppoeEnabled on a customer
        // with no secret: it did nothing, silently, and they kept working.
        verify(mikrotikService).setStaticEnabled(eq(acme), any(), eq(false));
        verify(mikrotikService, never()).setPppoeEnabled(any(), anyBoolean());
    }

    @Test
    @DisplayName("a static customer is provisioned, throttled and removed by their own path")
    void staticUsesItsOwnPath() {
        Subscriber acme = staticCustomer();

        provisioning.provision(acme);
        provisioning.setRate(acme, "1M/1M");
        provisioning.remove(acme);

        verify(mikrotikService).provisionStatic(eq(acme), any());
        verify(mikrotikService).setStaticRate(acme, "1M/1M");
        verify(mikrotikService).removeStatic(eq(acme), any());
        verify(mikrotikService, never()).provisionPppoe(any());
    }

    // --- where they sit ---

    @Test
    @DisplayName("the placement carries the mask, gateway and interface from the subnet")
    void placementComesFromTheSubnet() {
        MikrotikService.StaticPlacement p = provisioning.placementFor(staticCustomer());

        assertThat(p.address()).isEqualTo("41.90.64.12");
        assertThat(p.prefix()).isEqualTo(26);
        assertThat(p.gateway()).isEqualTo("41.90.64.1");
        assertThat(p.interfaceName()).isEqualTo("bridge-static");
        assertThat(p.macAddress()).isEqualTo("aa:bb:cc:dd:ee:ff");
    }

    @Test
    @DisplayName("a customer with no address has no placement rather than a guessed one")
    void noAddressNoPlacement() {
        Subscriber acme = staticCustomer();
        acme.setStaticIp(null);

        assertThat(provisioning.placementFor(acme)).isNull();
    }

    @Test
    @DisplayName("an address typed in by hand still finds its subnet")
    void addressWithoutAnAssignmentStillResolves() {
        when(assignments.findBySubscriberId(anyLong())).thenReturn(List.of());

        MikrotikService.StaticPlacement p = provisioning.placementFor(staticCustomer());

        // Falls back to a containment search, so an address set directly on the
        // subscriber is not silently unprovisionable.
        assertThat(p).isNotNull();
        assertThat(p.prefix()).isEqualTo(26);
    }

    // --- what the customer is told ---

    @Test
    @DisplayName("the customer settings are the four values they type into their router")
    void customerSettings() {
        Map<String, Object> s = provisioning.customerSettings(staticCustomer());

        assertThat(s.get("usable")).isEqualTo(true);
        assertThat(s.get("ipAddress")).isEqualTo("41.90.64.12");
        // A /26, not the /24 somebody would assume. Getting this wrong gives the
        // customer a gateway they cannot reach, which looks exactly like a dead
        // link and sends a van out.
        assertThat(s.get("subnetMask")).isEqualTo("255.255.255.192");
        assertThat(s.get("gateway")).isEqualTo("41.90.64.1");
        assertThat(s.get("vlanId")).isEqualTo(120);
        assertThat(s.get("pinnedToDevice")).isEqualTo(true);
    }

    @Test
    @DisplayName("a PPPoE customer is told there is nothing to type in")
    void pppoeHasNoSettings() {
        Map<String, Object> s = provisioning.customerSettings(pppoeCustomer());

        assertThat(s.get("usable")).isEqualTo(false);
        assertThat((String) s.get("note")).contains("dials in");
    }

    @Test
    @DisplayName("a static customer with no address says what is missing")
    void staticWithNoAddress() {
        Subscriber acme = staticCustomer();
        acme.setStaticIp(null);

        Map<String, Object> s = provisioning.customerSettings(acme);

        assertThat(s.get("usable")).isEqualTo(false);
        assertThat((String) s.get("note")).contains("No address allocated");
    }

    @Test
    @DisplayName("an address in no known subnet says so rather than inventing a mask")
    void addressOutsideEverySubnet() {
        Subscriber acme = staticCustomer();
        acme.setStaticIp("10.99.99.99");
        when(assignments.findBySubscriberId(anyLong())).thenReturn(List.of());

        Map<String, Object> s = provisioning.customerSettings(acme);

        assertThat(s.get("usable")).isEqualTo(false);
        assertThat((String) s.get("note")).contains("not in any subnet");
    }

    @Test
    @DisplayName("an unpinned static address is flagged, because the neighbour can take it")
    void unpinnedIsFlagged() {
        Subscriber acme = staticCustomer();
        acme.setMacAddress(null);

        assertThat(provisioning.customerSettings(acme).get("pinnedToDevice")).isEqualTo(false);
    }

    // --- masks ---

    @Test
    @DisplayName("prefixes convert to the dotted masks a customer's router asks for")
    void masks() {
        assertThat(SubscriberProvisioningService.maskOf(24)).isEqualTo("255.255.255.0");
        assertThat(SubscriberProvisioningService.maskOf(26)).isEqualTo("255.255.255.192");
        assertThat(SubscriberProvisioningService.maskOf(30)).isEqualTo("255.255.255.252");
        assertThat(SubscriberProvisioningService.maskOf(22)).isEqualTo("255.255.252.0");
        assertThat(SubscriberProvisioningService.maskOf(16)).isEqualTo("255.255.0.0");
    }

    @Test
    @DisplayName("only PPPoE needs a reconnect for a speed change")
    void reconnectOnlyForPppoe() {
        // A queue applies to traffic; a PPP profile applies at dial-in. Telling a
        // customer the wrong one produces a support call either way.
        assertThat(provisioning.rateChangeNeedsReconnect(pppoeCustomer())).isTrue();
        assertThat(provisioning.rateChangeNeedsReconnect(staticCustomer())).isFalse();
    }
}
