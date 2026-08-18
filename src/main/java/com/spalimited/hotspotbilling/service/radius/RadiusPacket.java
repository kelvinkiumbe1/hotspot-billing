package com.spalimited.hotspotbilling.service.radius;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One RADIUS packet, on or off the wire (RFC 2865 / 2866).
 *
 * <p>The format is deliberately simple — a 20-byte header and a list of
 * type/length/value attributes — and the whole of its security is a shared
 * secret folded into MD5. That is weak by any modern standard and is also
 * non-negotiable: it is what every access point, switch and BRAS in the field
 * speaks, and a server that declined to do it would work with nothing.
 *
 * <p>What it does mean is that RADIUS belongs on a management network, and
 * that every place the secret is used has to be exactly right. The mistakes
 * here are silent ones: a wrong response authenticator is not an error the NAS
 * reports, it is a login that hangs.
 */
public final class RadiusPacket {

    // --- Codes ---
    public static final int ACCESS_REQUEST = 1;
    public static final int ACCESS_ACCEPT = 2;
    public static final int ACCESS_REJECT = 3;
    public static final int ACCOUNTING_REQUEST = 4;
    public static final int ACCOUNTING_RESPONSE = 5;
    public static final int DISCONNECT_REQUEST = 40;
    public static final int DISCONNECT_ACK = 41;
    public static final int DISCONNECT_NAK = 42;

    // --- Attribute types we care about ---
    public static final int USER_NAME = 1;
    public static final int USER_PASSWORD = 2;
    public static final int CHAP_PASSWORD = 3;
    public static final int NAS_IP_ADDRESS = 4;
    public static final int NAS_PORT = 5;
    public static final int SERVICE_TYPE = 6;
    public static final int FRAMED_PROTOCOL = 7;
    public static final int FRAMED_IP_ADDRESS = 8;
    public static final int REPLY_MESSAGE = 18;
    public static final int CLASS = 25;
    public static final int SESSION_TIMEOUT = 27;
    public static final int IDLE_TIMEOUT = 28;
    public static final int TERMINATION_ACTION = 29;
    public static final int CALLED_STATION_ID = 30;
    public static final int CALLING_STATION_ID = 31;
    public static final int NAS_IDENTIFIER = 32;
    public static final int ACCT_STATUS_TYPE = 40;
    public static final int ACCT_DELAY_TIME = 41;
    public static final int ACCT_INPUT_OCTETS = 42;
    public static final int ACCT_OUTPUT_OCTETS = 43;
    public static final int ACCT_SESSION_ID = 44;
    public static final int ACCT_AUTHENTIC = 45;
    public static final int ACCT_SESSION_TIME = 46;
    public static final int ACCT_INPUT_PACKETS = 47;
    public static final int ACCT_OUTPUT_PACKETS = 48;
    public static final int ACCT_TERMINATE_CAUSE = 49;
    public static final int ACCT_INPUT_GIGAWORDS = 52;
    public static final int ACCT_OUTPUT_GIGAWORDS = 53;
    public static final int CHAP_CHALLENGE = 60;
    public static final int NAS_PORT_TYPE = 61;
    public static final int PORT_LIMIT = 62;
    public static final int ACCT_INTERIM_INTERVAL = 85;
    public static final int NAS_PORT_ID = 87;
    public static final int VENDOR_SPECIFIC = 26;
    public static final int MESSAGE_AUTHENTICATOR = 80;

    // --- Acct-Status-Type values ---
    public static final int ACCT_START = 1;
    public static final int ACCT_STOP = 2;
    public static final int ACCT_INTERIM = 3;

    private static final int HEADER_LENGTH = 20;
    private static final int MAX_LENGTH = 4096;

    /** One attribute, kept as raw bytes so nothing is lost in translation. */
    public record Attribute(int type, byte[] value) {
    }

    private final int code;
    private final int identifier;
    private final byte[] authenticator;
    private final List<Attribute> attributes;

    public RadiusPacket(int code, int identifier, byte[] authenticator, List<Attribute> attributes) {
        this.code = code;
        this.identifier = identifier;
        this.authenticator = authenticator;
        this.attributes = attributes;
    }

    public int code() {
        return code;
    }

    public int identifier() {
        return identifier;
    }

    public byte[] authenticator() {
        return authenticator;
    }

    public List<Attribute> attributes() {
        return attributes;
    }

    // --- Decoding ---

    /**
     * Parses bytes off the wire.
     *
     * @throws IllegalArgumentException on anything malformed, which is treated
     *         as a packet to drop rather than an error to report — a RADIUS
     *         server on a public port will be sent rubbish and must not care.
     */
    public static RadiusPacket decode(byte[] data, int length) {
        if (length < HEADER_LENGTH) {
            throw new IllegalArgumentException("Packet shorter than a RADIUS header");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        int code = buffer.get() & 0xFF;
        int identifier = buffer.get() & 0xFF;
        int declared = buffer.getShort() & 0xFFFF;
        if (declared < HEADER_LENGTH || declared > MAX_LENGTH) {
            throw new IllegalArgumentException("Declared length " + declared + " is not credible");
        }
        if (declared > length) {
            // The header claims more than arrived. Trusting it would read past
            // the datagram, which is how a length field becomes a memory bug.
            throw new IllegalArgumentException("Packet is shorter than it claims");
        }
        byte[] authenticator = new byte[16];
        buffer.get(authenticator);

        List<Attribute> attributes = new ArrayList<>();
        int position = HEADER_LENGTH;
        while (position + 2 <= declared) {
            int type = buffer.get() & 0xFF;
            int attrLength = buffer.get() & 0xFF;
            if (attrLength < 2 || position + attrLength > declared) {
                throw new IllegalArgumentException("Attribute " + type + " has an impossible length");
            }
            byte[] value = new byte[attrLength - 2];
            buffer.get(value);
            attributes.add(new Attribute(type, value));
            position += attrLength;
        }
        return new RadiusPacket(code, identifier, authenticator, attributes);
    }

    // --- Encoding ---

    /**
     * Serialises a reply and stamps the Response Authenticator over it.
     *
     * <p>That value is MD5 of the whole response with the <em>request's</em>
     * authenticator in the header and the shared secret appended. It is what
     * proves to the NAS that the reply came from something holding the secret.
     */
    public byte[] encodeResponse(byte[] requestAuthenticator, String secret) {
        byte[] body = encodeAttributes();
        int length = HEADER_LENGTH + body.length;

        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.put((byte) code);
        buffer.put((byte) identifier);
        buffer.putShort((short) length);
        buffer.put(requestAuthenticator);
        buffer.put(body);

        byte[] toHash = new byte[length + secret.length()];
        System.arraycopy(buffer.array(), 0, toHash, 0, length);
        System.arraycopy(secret.getBytes(StandardCharsets.UTF_8), 0, toHash, length, secret.length());
        byte[] responseAuthenticator = md5(toHash);

        System.arraycopy(responseAuthenticator, 0, buffer.array(), 4, 16);
        return buffer.array();
    }

    /**
     * Serialises a request we originate — currently only Disconnect-Request.
     * Its authenticator is computed the same way but over sixteen zero bytes,
     * because there is no earlier request to point at.
     */
    public byte[] encodeRequest(String secret) {
        byte[] body = encodeAttributes();
        int length = HEADER_LENGTH + body.length;

        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.put((byte) code);
        buffer.put((byte) identifier);
        buffer.putShort((short) length);
        buffer.put(new byte[16]);
        buffer.put(body);

        byte[] toHash = new byte[length + secret.length()];
        System.arraycopy(buffer.array(), 0, toHash, 0, length);
        System.arraycopy(secret.getBytes(StandardCharsets.UTF_8), 0, toHash, length, secret.length());

        System.arraycopy(md5(toHash), 0, buffer.array(), 4, 16);
        return buffer.array();
    }

    private byte[] encodeAttributes() {
        int total = 0;
        for (Attribute attribute : attributes) {
            total += attribute.value().length + 2;
        }
        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (Attribute attribute : attributes) {
            buffer.put((byte) attribute.type());
            buffer.put((byte) (attribute.value().length + 2));
            buffer.put(attribute.value());
        }
        return buffer.array();
    }

    // --- Integrity ---

    /**
     * Whether an Accounting-Request really came from something holding the
     * secret. Unlike Access-Request, accounting packets carry a genuine MAC,
     * and checking it is the only thing stopping anyone on the network from
     * writing usage history for any customer they choose.
     */
    public boolean accountingAuthenticatorValid(byte[] raw, int length, String secret) {
        if (length < HEADER_LENGTH) {
            return false;
        }
        byte[] copy = new byte[length];
        System.arraycopy(raw, 0, copy, 0, length);
        // The field is zeroed for the hash, then the received value compared to it.
        java.util.Arrays.fill(copy, 4, 20, (byte) 0);

        byte[] toHash = new byte[length + secret.length()];
        System.arraycopy(copy, 0, toHash, 0, length);
        System.arraycopy(secret.getBytes(StandardCharsets.UTF_8), 0, toHash, length, secret.length());

        return MessageDigest.isEqual(md5(toHash), authenticator);
    }

    // --- Reading attributes ---

    public Optional<byte[]> raw(int type) {
        for (Attribute attribute : attributes) {
            if (attribute.type() == type) {
                return Optional.of(attribute.value());
            }
        }
        return Optional.empty();
    }

    public String string(int type) {
        return raw(type).map(v -> new String(v, StandardCharsets.UTF_8)).orElse(null);
    }

    public Integer integer(int type) {
        return raw(type).filter(v -> v.length == 4)
                .map(v -> ByteBuffer.wrap(v).getInt()).orElse(null);
    }

    /** An IPv4 address attribute, rendered the way a person writes one. */
    public String address(int type) {
        return raw(type).filter(v -> v.length == 4)
                .map(v -> (v[0] & 0xFF) + "." + (v[1] & 0xFF) + "." + (v[2] & 0xFF) + "." + (v[3] & 0xFF))
                .orElse(null);
    }

    /**
     * A 32-bit octet counter plus its gigawords companion.
     *
     * <p>RADIUS octet counters are 32 bits, so they roll over every 4.3 GB —
     * which one customer streaming video passes in an evening. The overflow
     * count lives in a separate attribute, and a server that reads only the
     * low word will under-bill every heavy user by whole multiples of 4 GB.
     */
    public long octets(int octetsType, int gigawordsType) {
        long low = unsigned(integer(octetsType));
        long high = unsigned(integer(gigawordsType));
        return high * 4_294_967_296L + low;
    }

    private static long unsigned(Integer value) {
        return value == null ? 0 : Integer.toUnsignedLong(value);
    }

    // --- Building ---

    public static Attribute text(int type, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 253) {
            bytes = java.util.Arrays.copyOf(bytes, 253);
        }
        return new Attribute(type, bytes);
    }

    public static Attribute number(int type, long value) {
        return new Attribute(type, ByteBuffer.allocate(4).putInt((int) value).array());
    }

    /**
     * A vendor-specific attribute (RFC 2865 §5.26): the vendor's id, then the
     * vendor's own type/length/value nested inside.
     */
    public static Attribute vendor(int vendorId, int vendorType, byte[] value) {
        ByteBuffer buffer = ByteBuffer.allocate(6 + value.length);
        buffer.putInt(vendorId);
        buffer.put((byte) vendorType);
        buffer.put((byte) (value.length + 2));
        buffer.put(value);
        return new Attribute(VENDOR_SPECIFIC, buffer.array());
    }

    public static Attribute vendorText(int vendorId, int vendorType, String value) {
        return vendor(vendorId, vendorType, value.getBytes(StandardCharsets.UTF_8));
    }

    public static Attribute vendorNumber(int vendorId, int vendorType, long value) {
        return vendor(vendorId, vendorType, ByteBuffer.allocate(4).putInt((int) value).array());
    }

    static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by RADIUS and missing from this JVM", e);
        }
    }
}
