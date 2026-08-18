package com.spalimited.hotspotbilling.service.ipam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Subnet arithmetic.
 *
 * <p>Worth testing to death because every mistake here ends as two customers
 * holding the same address, which arrives as an intermittent outage nobody can
 * reproduce rather than as an error anyone can see.
 */
class CidrTest {

    @Test
    @DisplayName("An ordinary subnet excludes its network and broadcast addresses")
    void ordinarySubnet() {
        Cidr cidr = Cidr.parse("10.20.0.0/24");

        assertThat(cidr.toString()).isEqualTo("10.20.0.0/24");
        assertThat(Cidr.toAddress(cidr.networkAddress())).isEqualTo("10.20.0.0");
        assertThat(Cidr.toAddress(cidr.broadcastAddress())).isEqualTo("10.20.0.255");
        // Neither of those is a host. Assigning one produces a customer who
        // cannot get online and a fault that looks like anything but this.
        assertThat(Cidr.toAddress(cidr.firstUsable())).isEqualTo("10.20.0.1");
        assertThat(Cidr.toAddress(cidr.lastUsable())).isEqualTo("10.20.0.254");
        assertThat(cidr.usableCount()).isEqualTo(254);
    }

    @Test
    @DisplayName("A subnet typed from a host address still means that subnet")
    void normalisesToTheNetworkAddress() {
        // An operator typing their own router's address and a /24 means the
        // subnet it sits in. Refusing that teaches them nothing.
        assertThat(Cidr.parse("10.20.0.5/24").toString()).isEqualTo("10.20.0.0/24");
        assertThat(Cidr.parse("192.168.88.1/24").toString()).isEqualTo("192.168.88.0/24");
    }

    @Test
    @DisplayName("A /31 point-to-point link has both addresses usable")
    void slashThirtyOne() {
        Cidr cidr = Cidr.parse("10.0.0.4/31");

        // RFC 3021. Treating this like a /24 leaves zero usable addresses in a
        // subnet the operator is actively using for a backhaul link.
        assertThat(Cidr.toAddress(cidr.firstUsable())).isEqualTo("10.0.0.4");
        assertThat(Cidr.toAddress(cidr.lastUsable())).isEqualTo("10.0.0.5");
        assertThat(cidr.usableCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("A /32 is one host, not zero")
    void slashThirtyTwo() {
        Cidr cidr = Cidr.parse("41.90.1.7/32");

        assertThat(Cidr.toAddress(cidr.firstUsable())).isEqualTo("41.90.1.7");
        assertThat(Cidr.toAddress(cidr.lastUsable())).isEqualTo("41.90.1.7");
        assertThat(cidr.usableCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A /30 leaves the two usable addresses a small link needs")
    void slashThirty() {
        Cidr cidr = Cidr.parse("10.0.0.0/30");

        assertThat(Cidr.toAddress(cidr.firstUsable())).isEqualTo("10.0.0.1");
        assertThat(Cidr.toAddress(cidr.lastUsable())).isEqualTo("10.0.0.2");
        assertThat(cidr.usableCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Addresses above 127.x do not come out negative")
    void highAddressesStayPositive() {
        // The classic bug: an int is signed, so 255.255.255.255 goes negative
        // and every comparison after that inverts. Held as longs for exactly
        // this reason.
        Cidr cidr = Cidr.parse("255.255.255.0/24");

        assertThat(cidr.networkAddress()).isPositive();
        assertThat(cidr.broadcastAddress()).isPositive();
        assertThat(Cidr.toAddress(cidr.broadcastAddress())).isEqualTo("255.255.255.255");
        assertThat(cidr.contains("255.255.255.200")).isTrue();

        // And a public African range, which is where real static IPs live.
        Cidr public41 = Cidr.parse("197.248.16.0/22");
        assertThat(public41.contains("197.248.19.254")).isTrue();
        assertThat(public41.contains("197.248.20.1")).isFalse();
        assertThat(public41.usableCount()).isEqualTo(1022);
    }

    @Test
    @DisplayName("Containment is exact at both edges")
    void containment() {
        Cidr cidr = Cidr.parse("10.20.0.0/24");

        assertThat(cidr.contains("10.20.0.0")).isTrue();
        assertThat(cidr.contains("10.20.0.255")).isTrue();
        assertThat(cidr.contains("10.20.1.0")).isFalse();
        assertThat(cidr.contains("10.19.255.255")).isFalse();
        // Rubbish is not inside anything, rather than throwing at a call site
        // that only wanted a yes or no.
        assertThat(cidr.contains("not an address")).isFalse();
    }

    @Test
    @DisplayName("Overlap is detected whichever subnet is the larger")
    void overlapBothDirections() {
        Cidr big = Cidr.parse("10.0.0.0/16");
        Cidr small = Cidr.parse("10.0.1.0/24");

        // A check written one way round misses half of these, and the half it
        // misses is the half that lets the allocator hand out a live address.
        assertThat(big.overlaps(small)).isTrue();
        assertThat(small.overlaps(big)).isTrue();
    }

    @Test
    @DisplayName("Adjacent subnets do not overlap")
    void adjacentDoNotOverlap() {
        assertThat(Cidr.parse("10.20.0.0/24").overlaps(Cidr.parse("10.20.1.0/24"))).isFalse();
        assertThat(Cidr.parse("10.20.0.0/25").overlaps(Cidr.parse("10.20.0.128/25"))).isFalse();
    }

    @Test
    @DisplayName("A /0 contains everything, which is a shift the JVM gets wrong unguarded")
    void slashZero() {
        Cidr any = Cidr.parse("0.0.0.0/0");

        // Shifting by 32 in Java is a shift by zero, so an unguarded mask makes
        // /0 match nothing instead of everything.
        assertThat(any.contains("8.8.8.8")).isTrue();
        assertThat(any.contains("197.248.16.1")).isTrue();
    }

    @Test
    @DisplayName("Nonsense is refused with something an operator can act on")
    void refusesNonsense() {
        assertThatThrownBy(() -> Cidr.parse("10.20.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a prefix");
        assertThatThrownBy(() -> Cidr.parse("10.20.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 32");
        assertThatThrownBy(() -> Cidr.parse("10.20.0.300/24"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0-255");
        assertThatThrownBy(() -> Cidr.parse("10.20.0/24"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an IPv4 address");
        assertThatThrownBy(() -> Cidr.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("An address survives a round trip through its number")
    void roundTrip() {
        for (String address : new String[]{
                "0.0.0.0", "10.20.30.40", "127.0.0.1", "192.168.88.1",
                "197.248.16.255", "255.255.255.255"}) {
            assertThat(Cidr.toAddress(Cidr.toLong(address))).isEqualTo(address);
        }
    }

    @Test
    @DisplayName("Two ways of writing the same subnet are the same subnet")
    void equality() {
        assertThat(Cidr.parse("10.20.0.0/24")).isEqualTo(Cidr.parse("10.20.0.99/24"));
        assertThat(Cidr.parse("10.20.0.0/24")).isNotEqualTo(Cidr.parse("10.20.0.0/25"));
    }
}
