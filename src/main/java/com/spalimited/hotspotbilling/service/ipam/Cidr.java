package com.spalimited.hotspotbilling.service.ipam;

import java.util.Objects;

/**
 * An IPv4 subnet, and the arithmetic for asking what is inside it.
 *
 * <p>Kept apart from anything that touches a database so it can be tested to
 * death, which is warranted: every mistake in here ends as two customers
 * holding the same address, and that shows up as an intermittent outage nobody
 * can reproduce rather than as an error.
 *
 * <p>Addresses are held as longs rather than ints. An int is signed, so
 * 255.255.255.255 becomes negative and every comparison after that quietly
 * inverts — which is the classic way this code goes wrong.
 */
public final class Cidr {

    private final long network;
    private final int prefix;

    private Cidr(long network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    /**
     * Parses "10.20.0.0/24", tolerating an address that is not the network
     * address — an operator typing 10.20.0.5/24 means that subnet, and
     * refusing it teaches them nothing.
     */
    public static Cidr parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter a subnet like 10.20.0.0/24");
        }
        String[] parts = text.trim().split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException("A subnet needs a prefix, like 10.20.0.0/24");
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + parts[1] + "' is not a prefix length");
        }
        if (prefix < 0 || prefix > 32) {
            throw new IllegalArgumentException("A prefix is between 0 and 32");
        }
        long address = toLong(parts[0].trim());
        return new Cidr(address & mask(prefix), prefix);
    }

    public static long toLong(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("'" + address + "' is not an IPv4 address");
        }
        long value = 0;
        for (String octet : octets) {
            int part;
            try {
                part = Integer.parseInt(octet.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + address + "' is not an IPv4 address");
            }
            if (part < 0 || part > 255) {
                throw new IllegalArgumentException("'" + address + "' has an octet outside 0-255");
            }
            value = (value << 8) | part;
        }
        return value;
    }

    public static String toAddress(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF)
                + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }

    /** All ones in the top {@code prefix} bits. Kept in a long, deliberately. */
    private static long mask(int prefix) {
        return prefix == 0 ? 0L : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    public int prefix() {
        return prefix;
    }

    public long networkAddress() {
        return network;
    }

    public long broadcastAddress() {
        return network | (~mask(prefix) & 0xFFFFFFFFL);
    }

    /**
     * The first address that may be given to a host.
     *
     * <p>For an ordinary subnet that is network + 1, because the network
     * address itself is not a host. The two exceptions are real and both bite:
     * a /32 is a single host route, and a /31 is a point-to-point link where
     * both addresses are usable (RFC 3021) — treating either the way a /24 is
     * treated leaves zero usable addresses in a subnet an operator is using.
     */
    public long firstUsable() {
        return prefix >= 31 ? network : network + 1;
    }

    public long lastUsable() {
        return prefix >= 31 ? broadcastAddress() : broadcastAddress() - 1;
    }

    /** How many addresses could be given out, ignoring what already has been. */
    public long usableCount() {
        return lastUsable() - firstUsable() + 1;
    }

    public boolean contains(long address) {
        return (address & mask(prefix)) == network;
    }

    public boolean contains(String address) {
        try {
            return contains(toLong(address));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Whether two subnets share any address.
     *
     * <p>Not the same as either containing the other's network address in one
     * direction only: 10.0.0.0/16 and 10.0.1.0/24 overlap, and a check written
     * one way round misses half of those. Both directions are tested.
     */
    public boolean overlaps(Cidr other) {
        return contains(other.network) || other.contains(network);
    }

    @Override
    public String toString() {
        return toAddress(network) + "/" + prefix;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Cidr other && other.network == network && other.prefix == prefix;
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, prefix);
    }
}
