package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.TimeTicks;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SnmpClient} against something that actually speaks SNMP.
 *
 * <p>The agent is real, the UDP is real, and the encoding is real. What is
 * faked is only the switch on the other end — which is the one part that
 * cannot be in a test suite.
 */
class SnmpClientTest {

    private FakeSnmpAgent agent;
    private final SnmpClient client = new SnmpClient();

    @BeforeEach
    void startAgent() throws Exception {
        agent = new FakeSnmpAgent("s3cret");
        agent.set(SnmpClient.SYS_NAME, new OctetString("cabinet-sw-01"))
                .set(SnmpClient.SYS_DESCR, new OctetString("Fake 8-port switch\nline two"))
                .set(SnmpClient.SYS_LOCATION, new OctetString("Westlands cabinet"))
                .set(SnmpClient.SYS_CONTACT, new OctetString("noc@example.com"))
                // 4 days, 4 hours in hundredths of a second
                .set(SnmpClient.SYS_UPTIME, new TimeTicks(36_000_000L));
    }

    @AfterEach
    void stopAgent() throws Exception {
        agent.close();
    }

    private NetworkDevice device(String community) {
        return NetworkDevice.builder()
                .name("test").host("127.0.0.1").port(agent.port())
                .snmpVersion(NetworkDevice.Version.V2C)
                .community(community)
                .build();
    }

    @Test
    @DisplayName("A device that answers reports who it is and how long it has been up")
    void probeSucceeds() {
        SnmpClient.Probe probe = client.probe(device("s3cret"));

        assertThat(probe.reachable()).isTrue();
        assertThat(probe.error()).isNull();
        assertThat(probe.sysName()).isEqualTo("cabinet-sw-01");
        assertThat(probe.sysLocation()).isEqualTo("Westlands cabinet");
        assertThat(probe.uptimeSeconds()).isEqualTo(360_000);
    }

    @Test
    @DisplayName("An embedded newline is flattened, but the spaces in a description survive")
    void descriptionIsCleanedNotMangled() {
        SnmpClient.Probe probe = client.probe(device("s3cret"));
        assertThat(probe.sysDescr()).isEqualTo("Fake 8-port switch line two");
    }

    @Test
    @DisplayName("The wrong community reads as unreachable, with something an operator can act on")
    void wrongCommunity() {
        SnmpClient.Probe probe = client.probe(device("wrong"));

        assertThat(probe.reachable()).isFalse();
        // A real agent answers a bad community with silence, so this is
        // indistinguishable from the device being down — and the message has
        // to cover both, since we genuinely cannot tell which it was.
        assertThat(probe.error()).contains("No answer").contains("SNMP is enabled");
    }

    @Test
    @DisplayName("Walking the interface table returns every port with its human label")
    void walksPorts() throws Exception {
        agent.addPort(1, "ether1", "ether1", "uplink to core", 1, 1,
                        1_000_000_000L, 5_000, 9_000, 0, 0)
                .addPort(2, "ether2", "ether2", "AP roof west", 1, 2,
                        100_000_000L, 100, 200, 7, 0)
                .addPort(3, "ether3", "ether3", "", 2, 2, 0, 0, 0, 0, 0);

        List<SnmpClient.Port> ports = client.ports(device("s3cret"));

        assertThat(ports).hasSize(3);
        SnmpClient.Port uplink = ports.get(0);
        assertThat(uplink.ifIndex()).isEqualTo(1);
        assertThat(uplink.alias()).isEqualTo("uplink to core");
        assertThat(uplink.adminUp()).isTrue();
        assertThat(uplink.operUp()).isTrue();
        assertThat(uplink.speedBps()).isEqualTo(1_000_000_000L);
        assertThat(uplink.inOctets()).isEqualTo(5_000);

        SnmpClient.Port cabled = ports.get(1);
        assertThat(cabled.adminUp()).isTrue();
        // Switched on but no link — the state worth being told about.
        assertThat(cabled.operUp()).isFalse();
        assertThat(cabled.inErrors()).isEqualTo(7);

        SnmpClient.Port off = ports.get(2);
        assertThat(off.adminUp()).isFalse();
    }

    @Test
    @DisplayName("Where the device offers 64-bit counters, those are the ones read")
    void prefersHighCapacityCounters() throws Exception {
        // The 32-bit counter says 1,000; the 64-bit one says it has been round
        // several times. Reading the narrow one would under-report by 12 GB.
        agent.addPort(1, "ether1", "ether1", "uplink", 1, 1,
                        1_000_000_000L, 1_000, 2_000, 0, 0)
                .addHighCapacity(1, 12_884_903_912L, 12_884_904_912L, 10_000);

        List<SnmpClient.Port> ports = client.ports(device("s3cret"));

        assertThat(ports).hasSize(1);
        assertThat(ports.get(0).sixtyFourBit()).isTrue();
        assertThat(ports.get(0).inOctets()).isEqualTo(12_884_903_912L);
        // ifHighSpeed is in Mbps and is the only one that can express 10G;
        // ifSpeed would have reported a gigabit link.
        assertThat(ports.get(0).speedBps()).isEqualTo(10_000_000_000L);
    }

    @Test
    @DisplayName("A device with no ifXTable still reports its ports, on the narrow counters")
    void survivesWithoutIfXTable() throws Exception {
        agent.addPort(1, "FastEthernet0/1", "", "", 1, 1, 100_000_000L, 4_000, 8_000, 0, 0);

        List<SnmpClient.Port> ports = client.ports(device("s3cret"));

        assertThat(ports).hasSize(1);
        assertThat(ports.get(0).sixtyFourBit()).isFalse();
        assertThat(ports.get(0).inOctets()).isEqualTo(4_000);
        assertThat(ports.get(0).descr()).isEqualTo("FastEthernet0/1");
    }
}
