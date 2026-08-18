package com.spalimited.hotspotbilling.service.radius;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The RADIUS wire format, checked against packets built by hand.
 *
 * <p>Every failure this guards against is silent. A wrong response
 * authenticator is not an error a NAS reports — it is a login that hangs. A
 * missed gigawords attribute is not a crash — it is a customer under-billed by
 * exact multiples of four gigabytes. So the arithmetic is checked here rather
 * than discovered in a month of unexplained complaints.
 */
class RadiusPacketTest {

    private static final String SECRET = "testing123";

    /** Assembles a request the way a NAS would, so the parser meets real bytes. */
    private static byte[] request(int code, int id, byte[] authenticator,
                                  List<RadiusPacket.Attribute> attributes) {
        int body = attributes.stream().mapToInt(a -> a.value().length + 2).sum();
        ByteBuffer buffer = ByteBuffer.allocate(20 + body);
        buffer.put((byte) code);
        buffer.put((byte) id);
        buffer.putShort((short) (20 + body));
        buffer.put(authenticator);
        for (RadiusPacket.Attribute attribute : attributes) {
            buffer.put((byte) attribute.type());
            buffer.put((byte) (attribute.value().length + 2));
            buffer.put(attribute.value());
        }
        return buffer.array();
    }

    private static byte[] authenticator(int seed) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (seed + i * 7);
        }
        return out;
    }

    @Test
    @DisplayName("A request built by a NAS parses back into the same attributes")
    void roundTrip() {
        byte[] auth = authenticator(3);
        byte[] wire = request(RadiusPacket.ACCESS_REQUEST, 42, auth, List.of(
                RadiusPacket.text(RadiusPacket.USER_NAME, "ABC123"),
                RadiusPacket.number(RadiusPacket.NAS_PORT, 7)));

        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        assertThat(packet.code()).isEqualTo(RadiusPacket.ACCESS_REQUEST);
        assertThat(packet.identifier()).isEqualTo(42);
        assertThat(packet.authenticator()).isEqualTo(auth);
        assertThat(packet.string(RadiusPacket.USER_NAME)).isEqualTo("ABC123");
        assertThat(packet.integer(RadiusPacket.NAS_PORT)).isEqualTo(7);
    }

    @Test
    @DisplayName("A packet claiming to be longer than it is gets rejected, not read past")
    void lyingLengthField() {
        byte[] wire = request(RadiusPacket.ACCESS_REQUEST, 1, authenticator(1), List.of());
        // Claim 200 bytes when 20 arrived. Believing it reads other memory.
        wire[2] = 0;
        wire[3] = (byte) 200;

        assertThatThrownBy(() -> RadiusPacket.decode(wire, wire.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than it claims");
    }

    @Test
    @DisplayName("An attribute running past the end of the packet is rejected")
    void attributeOverrunsPacket() {
        byte[] wire = request(RadiusPacket.ACCESS_REQUEST, 1, authenticator(1), List.of(
                RadiusPacket.text(RadiusPacket.USER_NAME, "bob")));
        wire[21] = (byte) 200; // that attribute now claims 200 bytes

        assertThatThrownBy(() -> RadiusPacket.decode(wire, wire.length))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("impossible length");
    }

    @Test
    @DisplayName("Anything shorter than a header is rejected rather than parsed")
    void runtPacket() {
        assertThatThrownBy(() -> RadiusPacket.decode(new byte[8], 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The response authenticator is MD5 over the reply plus the secret")
    void responseAuthenticator() throws Exception {
        byte[] requestAuth = authenticator(9);
        RadiusPacket reply = new RadiusPacket(RadiusPacket.ACCESS_ACCEPT, 42, requestAuth,
                List.of(RadiusPacket.number(RadiusPacket.SESSION_TIMEOUT, 3600)));

        byte[] encoded = reply.encodeResponse(requestAuth, SECRET);

        // Recompute it the way a NAS would: zero out nothing, but substitute
        // the request authenticator back into the header before hashing.
        byte[] check = encoded.clone();
        System.arraycopy(requestAuth, 0, check, 4, 16);
        byte[] toHash = new byte[check.length + SECRET.length()];
        System.arraycopy(check, 0, toHash, 0, check.length);
        System.arraycopy(SECRET.getBytes(StandardCharsets.UTF_8), 0, toHash, check.length, SECRET.length());
        byte[] expected = MessageDigest.getInstance("MD5").digest(toHash);

        byte[] actual = new byte[16];
        System.arraycopy(encoded, 4, actual, 0, 16);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("An accounting packet signed with our secret passes its integrity check")
    void accountingAuthenticatorAccepted() {
        byte[] wire = signedAccounting(SECRET);
        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        assertThat(packet.accountingAuthenticatorValid(wire, wire.length, SECRET)).isTrue();
    }

    @Test
    @DisplayName("One signed with a different secret is refused — this is the only real check RADIUS has")
    void accountingAuthenticatorRejected() {
        byte[] wire = signedAccounting("somebody-elses-secret");
        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        // Without this, anyone who can reach the port could write usage history
        // for any customer — draining a stranger's pass, or clearing their own.
        assertThat(packet.accountingAuthenticatorValid(wire, wire.length, SECRET)).isFalse();
    }

    @Test
    @DisplayName("A tampered byte breaks the accounting signature")
    void accountingTamperDetected() {
        byte[] wire = signedAccounting(SECRET);
        // Move the session time attribute's value; the signature must not survive.
        wire[wire.length - 1] ^= 0x01;
        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        assertThat(packet.accountingAuthenticatorValid(wire, wire.length, SECRET)).isFalse();
    }

    /** Builds an Accounting-Request with a correct authenticator for the secret. */
    private static byte[] signedAccounting(String secret) {
        byte[] wire = request(RadiusPacket.ACCOUNTING_REQUEST, 5, new byte[16], List.of(
                RadiusPacket.text(RadiusPacket.ACCT_SESSION_ID, "8100000a"),
                RadiusPacket.number(RadiusPacket.ACCT_STATUS_TYPE, RadiusPacket.ACCT_INTERIM),
                RadiusPacket.number(RadiusPacket.ACCT_SESSION_TIME, 1200)));
        byte[] toHash = new byte[wire.length + secret.length()];
        System.arraycopy(wire, 0, toHash, 0, wire.length);
        System.arraycopy(secret.getBytes(StandardCharsets.UTF_8), 0, toHash, wire.length, secret.length());
        System.arraycopy(RadiusPacket.md5(toHash), 0, wire, 4, 16);
        return wire;
    }

    @Test
    @DisplayName("Octet counters combine with their gigawords companion")
    void gigawords() {
        byte[] wire = request(RadiusPacket.ACCOUNTING_REQUEST, 1, new byte[16], List.of(
                RadiusPacket.number(RadiusPacket.ACCT_INPUT_OCTETS, 1_000),
                RadiusPacket.number(RadiusPacket.ACCT_INPUT_GIGAWORDS, 3)));
        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        // Three rollovers plus a bit. Reading only the low word would report
        // one kilobyte for a customer who moved twelve gigabytes.
        assertThat(packet.octets(RadiusPacket.ACCT_INPUT_OCTETS, RadiusPacket.ACCT_INPUT_GIGAWORDS))
                .isEqualTo(3 * 4_294_967_296L + 1_000);
    }

    @Test
    @DisplayName("A counter in the top half of the 32-bit range is not read as negative")
    void unsignedCounters() {
        byte[] wire = request(RadiusPacket.ACCOUNTING_REQUEST, 1, new byte[16], List.of(
                RadiusPacket.number(RadiusPacket.ACCT_OUTPUT_OCTETS, 4_000_000_000L)));
        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        assertThat(packet.octets(RadiusPacket.ACCT_OUTPUT_OCTETS, RadiusPacket.ACCT_OUTPUT_GIGAWORDS))
                .isEqualTo(4_000_000_000L);
    }

    @Test
    @DisplayName("An IPv4 attribute reads back the way a person writes an address")
    void addressAttribute() {
        byte[] wire = request(RadiusPacket.ACCESS_REQUEST, 1, new byte[16], List.of(
                new RadiusPacket.Attribute(RadiusPacket.FRAMED_IP_ADDRESS,
                        new byte[]{10, (byte) 200, 0, (byte) 254})));
        RadiusPacket packet = RadiusPacket.decode(wire, wire.length);

        assertThat(packet.address(RadiusPacket.FRAMED_IP_ADDRESS)).isEqualTo("10.200.0.254");
    }

    @Test
    @DisplayName("A vendor attribute nests the vendor's own type and length inside")
    void vendorSpecific() {
        RadiusPacket.Attribute attribute = RadiusPacket.vendorText(14988, 8, "5M/10M");
        byte[] value = attribute.value();

        assertThat(attribute.type()).isEqualTo(RadiusPacket.VENDOR_SPECIFIC);
        assertThat(ByteBuffer.wrap(value).getInt()).isEqualTo(14988);
        assertThat(value[4]).isEqualTo((byte) 8);
        assertThat(value[5]).isEqualTo((byte) 8); // "5M/10M" is 6 bytes, plus 2
        assertThat(new String(value, 6, 6, StandardCharsets.UTF_8)).isEqualTo("5M/10M");
    }
}
