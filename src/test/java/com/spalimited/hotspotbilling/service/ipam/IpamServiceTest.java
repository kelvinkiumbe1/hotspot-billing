package com.spalimited.hotspotbilling.service.ipam;

import com.spalimited.hotspotbilling.domain.IpAssignment;
import com.spalimited.hotspotbilling.domain.IpSubnet;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.IpAssignmentRepository;
import com.spalimited.hotspotbilling.repository.IpSubnetRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Allocating addresses without handing the same one out twice.
 *
 * <p>The interesting cases are all refusals: the network address, the broadcast
 * address, the gateway, an address outside the subnet, and a subnet that
 * overlaps another. Each of those, allowed through, produces a customer who
 * cannot get online and a fault that looks like anything except addressing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IpamServiceTest {

    @Mock
    private IpSubnetRepository subnets;

    @Mock
    private IpAssignmentRepository assignments;

    @Mock
    private SubscriberRepository subscribers;

    @InjectMocks
    private IpamService ipam;

    private final List<IpAssignment> stored = new ArrayList<>();
    private final List<IpSubnet> storedSubnets = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private IpSubnet subnet;

    @BeforeEach
    void setUp() {
        subnet = IpSubnet.builder().id(1L).name("Westlands static")
                .cidr("10.20.0.0/24").gateway("10.20.0.1")
                .purpose(IpSubnet.Purpose.STATIC).build();
        storedSubnets.add(subnet);

        when(subnets.findById(1L)).thenReturn(Optional.of(subnet));
        when(subnets.findAll()).thenReturn(storedSubnets);
        when(subnets.save(any())).thenAnswer(i -> {
            IpSubnet s = i.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.incrementAndGet());
            }
            return s;
        });
        when(assignments.findBySubnetIdOrderByAddressAsc(anyLong())).thenReturn(stored);
        when(assignments.countBySubnetId(anyLong())).thenAnswer(i -> (long) stored.size());
        when(assignments.save(any())).thenAnswer(i -> {
            IpAssignment a = i.getArgument(0);
            if (a.getId() == null) {
                a.setId(ids.incrementAndGet());
            }
            stored.add(a);
            return a;
        });
        when(subscribers.findById(anyLong())).thenReturn(Optional.empty());
    }

    private void take(String address) {
        stored.add(IpAssignment.builder().id(ids.incrementAndGet())
                .subnetId(1L).address(address).kind(IpAssignment.Kind.ASSIGNED).build());
    }

    @Test
    @DisplayName("The first address offered is the first host, not the network address")
    void firstOfferIsAHost() {
        assertThat(ipam.nextFree(1L)).contains("10.20.0.1");
    }

    @Test
    @DisplayName("Taken addresses are skipped, in order")
    void skipsTaken() {
        take("10.20.0.1");
        take("10.20.0.2");
        take("10.20.0.4");

        assertThat(ipam.nextFree(1L)).contains("10.20.0.3");
    }

    @Test
    @DisplayName("A full subnet offers nothing rather than something wrong")
    void fullSubnetOffersNothing() {
        IpSubnet tiny = IpSubnet.builder().id(2L).name("Link").cidr("10.0.0.0/30").build();
        when(subnets.findById(2L)).thenReturn(Optional.of(tiny));
        stored.add(IpAssignment.builder().subnetId(2L).address("10.0.0.1").build());
        stored.add(IpAssignment.builder().subnetId(2L).address("10.0.0.2").build());

        assertThat(ipam.nextFree(2L)).isEmpty();
    }

    @Test
    @DisplayName("A specific address inside the subnet is granted")
    void assignsSpecific() {
        IpAssignment saved = ipam.assign(1L, "10.20.0.50", IpAssignment.Kind.ASSIGNED,
                null, null, "printer", null, null, "tester");

        assertThat(saved.getAddress()).isEqualTo("10.20.0.50");
        assertThat(saved.getHostname()).isEqualTo("printer");
    }

    @Test
    @DisplayName("An address outside the subnet is refused, with the subnet named")
    void refusesOutside() {
        assertThatThrownBy(() -> ipam.assign(1L, "10.21.0.5", null,
                null, null, null, null, null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not inside 10.20.0.0/24");
    }

    @Test
    @DisplayName("The network and broadcast addresses are refused by name")
    void refusesNetworkAndBroadcast() {
        // Assigning either produces a customer who cannot get online, and a
        // fault report that mentions everything except the address.
        assertThatThrownBy(() -> ipam.assign(1L, "10.20.0.0", null,
                null, null, null, null, null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("network address");

        assertThatThrownBy(() -> ipam.assign(1L, "10.20.0.255", null,
                null, null, null, null, null, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broadcast address");
    }

    @Test
    @DisplayName("Creating a subnet reserves its gateway straight away")
    void gatewayIsReserved() {
        storedSubnets.clear();
        IpSubnet fresh = IpSubnet.builder().name("New site")
                .cidr("192.168.50.0/24").gateway("192.168.50.1").build();

        ipam.create(fresh, "tester");

        // Handing a customer the router's own address takes the site off the air.
        assertThat(stored).anySatisfy(a -> {
            assertThat(a.getAddress()).isEqualTo("192.168.50.1");
            assertThat(a.getKind()).isEqualTo(IpAssignment.Kind.GATEWAY);
        });
    }

    @Test
    @DisplayName("A gateway outside its own subnet is refused")
    void gatewayMustBeInside() {
        storedSubnets.clear();
        IpSubnet wrong = IpSubnet.builder().name("Typo")
                .cidr("192.168.50.0/24").gateway("192.168.5.1").build();

        assertThatThrownBy(() -> ipam.create(wrong, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not inside");
    }

    @Test
    @DisplayName("An overlapping subnet is refused, and told which one it clashes with")
    void refusesOverlap() {
        // 10.20.0.0/24 already exists. A /16 containing it means the allocator
        // believes an address is free in one while it is live in the other.
        IpSubnet overlapping = IpSubnet.builder().name("Everything").cidr("10.20.0.0/16").build();

        assertThatThrownBy(() -> ipam.create(overlapping, "tester"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Westlands static");
    }

    @Test
    @DisplayName("A subnet is stored normalised, so one block cannot exist twice")
    void storedNormalised() {
        storedSubnets.clear();
        IpSubnet typed = IpSubnet.builder().name("Typed from a host").cidr("172.16.4.77/24").build();

        IpSubnet saved = ipam.create(typed, "tester");

        assertThat(saved.getCidr()).isEqualTo("172.16.4.0/24");
    }

    @Test
    @DisplayName("A non-overlapping subnet is accepted")
    void acceptsNeighbour() {
        storedSubnets.clear();
        storedSubnets.add(subnet);
        IpSubnet neighbour = IpSubnet.builder().name("Next door").cidr("10.20.1.0/24").build();

        assertThat(ipam.create(neighbour, "tester").getCidr()).isEqualTo("10.20.1.0/24");
    }

    @Test
    @DisplayName("Releasing the gateway is refused")
    void cannotReleaseTheGateway() {
        IpAssignment gateway = IpAssignment.builder().id(99L).subnetId(1L)
                .address("10.20.0.1").kind(IpAssignment.Kind.GATEWAY).build();
        when(assignments.findById(99L)).thenReturn(Optional.of(gateway));

        assertThatThrownBy(() -> ipam.release(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("take the site down");
    }

    @Test
    @DisplayName("A subscriber's static address is mirrored onto their record for RADIUS")
    void mirrorsOntoSubscriber() {
        Subscriber sub = Subscriber.builder().id(7L).fullName("Test").build();
        when(subscribers.findById(7L)).thenReturn(Optional.of(sub));
        when(subscribers.save(any())).thenAnswer(i -> i.getArgument(0));

        ipam.assign(1L, "10.20.0.77", IpAssignment.Kind.ASSIGNED,
                7L, null, null, null, null, "tester");

        // Without this, RADIUS would have to look the assignment up on every
        // login, and the address would not follow the customer.
        assertThat(sub.getStaticIp()).isEqualTo("10.20.0.77");
    }

    @Test
    @DisplayName("Utilisation counts hosts, not the whole address space")
    void utilisation() {
        take("10.20.0.1");
        take("10.20.0.2");

        Map<String, Object> described = ipam.describe(subnet);

        // 254 usable in a /24, not 256.
        assertThat(described.get("usable")).isEqualTo(254L);
        assertThat(described.get("used")).isEqualTo(2L);
        assertThat(described.get("free")).isEqualTo(252L);
        assertThat(described.get("percentUsed")).isEqualTo(0);
    }

    @Test
    @DisplayName("A nearly-full subnet does not round up to completely full")
    void percentRoundsDown() {
        // 253 of 254. Reading as 100% would say there is nothing left when
        // there is exactly one address available.
        for (int i = 1; i <= 253; i++) {
            take("10.20.0." + i);
        }
        assertThat(ipam.describe(subnet).get("percentUsed")).isEqualTo(99);
        assertThat(ipam.nextFree(1L)).contains("10.20.0.254");
    }

}
