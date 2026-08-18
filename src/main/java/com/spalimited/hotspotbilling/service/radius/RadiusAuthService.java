package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.Plan;
import com.spalimited.hotspotbilling.domain.RadiusClient;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Deciding whether a login is allowed, and on what terms.
 *
 * <p>This is the same decision the MikroTik integration makes today by writing
 * users onto the router in advance. Doing it here instead means it is made once
 * rather than copied onto every router, it is made at the moment of login
 * rather than at the moment of sale, and it is made for any vendor's hardware
 * rather than one.
 *
 * <p>The reply carries the terms as well as the verdict: how long the customer
 * has left, how fast they may go, how many devices. A NAS that honours those
 * enforces the plan without this system having to reach back into it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RadiusAuthService {

    // Vendor ids, from IANA's enterprise numbers.
    private static final int VENDOR_MIKROTIK = 14988;
    private static final int VENDOR_WISPR = 14122;
    private static final int VENDOR_CISCO = 9;

    private static final int MIKROTIK_RATE_LIMIT = 8;
    private static final int WISPR_BANDWIDTH_MAX_UP = 7;
    private static final int WISPR_BANDWIDTH_MAX_DOWN = 8;
    private static final int CISCO_AVPAIR = 1;

    private final VoucherRepository vouchers;
    private final SubscriberRepository subscribers;

    /** The verdict, and everything the NAS should be told along with it. */
    public record Decision(boolean accept, String message,
                           List<RadiusPacket.Attribute> attributes,
                           Long voucherId, Long subscriberId,
                           com.spalimited.hotspotbilling.domain.RadiusSession.Kind kind) {

        static Decision reject(String why) {
            return new Decision(false, why, List.of(), null, null, null);
        }
    }

    /**
     * Works out who is logging in and whether they may.
     *
     * <p>A voucher code and a PPPoE username live in different tables and are
     * both "the username" as far as RADIUS is concerned, so both are tried. A
     * name matching neither is rejected with the same wording as a wrong
     * password, deliberately — telling an attacker which codes exist is how a
     * voucher system gets brute-forced.
     */
    @Transactional
    public Decision authorise(String username, PasswordCheck check, RadiusClient client,
                              int interimSeconds) {
        if (username == null || username.isBlank()) {
            return Decision.reject("No username");
        }

        Voucher voucher = vouchers.findByCode(username.trim()).orElse(null);
        if (voucher != null) {
            return authoriseVoucher(voucher, check, client, interimSeconds);
        }

        Subscriber subscriber = subscribers.findByPppoeUsername(username.trim()).orElse(null);
        if (subscriber != null) {
            return authoriseSubscriber(subscriber, check, client, interimSeconds);
        }

        return Decision.reject("Wrong username or password");
    }

    /** Who a username belongs to, with no password involved. */
    public record Owner(Long voucherId, Long subscriberId,
                        com.spalimited.hotspotbilling.domain.RadiusSession.Kind kind) {
    }

    /**
     * Works out who an accounting packet is about when the NAS did not echo
     * the Class attribute we sent at login.
     *
     * <p>Most routers do echo it, and it is the reliable answer. But some do
     * not, and a session that was already running when this service restarted
     * has no Class we ever issued. Falling back to the username costs nothing —
     * for a hotspot pass the username <em>is</em> the code — and the
     * alternative is silently discarding the usage, which shows up much later
     * as a customer whose pass never seems to run down.
     */
    @Transactional(readOnly = true)
    public Owner ownerOf(String username) {
        if (username == null || username.isBlank()) {
            return new Owner(null, null, null);
        }
        Voucher voucher = vouchers.findByCode(username.trim()).orElse(null);
        if (voucher != null) {
            return new Owner(voucher.getId(), null,
                    com.spalimited.hotspotbilling.domain.RadiusSession.Kind.HOTSPOT);
        }
        Subscriber subscriber = subscribers.findByPppoeUsername(username.trim()).orElse(null);
        if (subscriber != null) {
            return new Owner(null, subscriber.getId(),
                    com.spalimited.hotspotbilling.domain.RadiusSession.Kind.PPPOE);
        }
        return new Owner(null, null, null);
    }

    /** How the caller verifies a password without this service seeing the packet. */
    public interface PasswordCheck {
        /** True when the offered credential matches this known-good password. */
        boolean matches(String knownPassword);

        /** True when the request used a scheme this server does not implement. */
        boolean unsupported();
    }

    private Decision authoriseVoucher(Voucher voucher, PasswordCheck check, RadiusClient client,
                                      int interimSeconds) {
        if (check.unsupported()) {
            return Decision.reject("Login method not supported — set the NAS to PAP or CHAP");
        }
        // A hotspot code is its own password: the customer is given one string
        // and types it into both boxes, or the NAS sends it as both.
        if (!check.matches(voucher.getCode())) {
            return Decision.reject("Wrong username or password");
        }

        if (voucher.getStatus() == Voucher.Status.EXPIRED) {
            return Decision.reject("That pass has been used up");
        }
        if (voucher.getExpiresAt() != null && voucher.getExpiresAt().isBefore(Instant.now())) {
            return Decision.reject("That pass has expired");
        }
        long remaining = voucher.getRemainingSeconds();
        if (remaining <= 0) {
            return Decision.reject("That pass has been used up");
        }

        Plan plan = voucher.getPlan();
        List<RadiusPacket.Attribute> attributes = new ArrayList<>();
        // The single most important attribute here: it is what makes a pass
        // actually end, on hardware we have no other way to reach.
        attributes.add(RadiusPacket.number(RadiusPacket.SESSION_TIMEOUT, remaining));
        attributes.add(RadiusPacket.number(RadiusPacket.ACCT_INTERIM_INTERVAL, interimSeconds));
        // Idle time is not charged, so a customer who walks away and comes back
        // is not billed for the gap — but the session must eventually close or
        // the seat is held forever.
        attributes.add(RadiusPacket.number(RadiusPacket.IDLE_TIMEOUT, 600));
        if (plan != null) {
            addRateLimit(attributes, client, plan.getBandwidth());
            int devices = plan.getEffectiveMaxDevices();
            if (devices > 0) {
                attributes.add(RadiusPacket.number(RadiusPacket.PORT_LIMIT, devices));
            }
        }

        return new Decision(true, "ok", attributes, voucher.getId(), null,
                com.spalimited.hotspotbilling.domain.RadiusSession.Kind.HOTSPOT);
    }

    private Decision authoriseSubscriber(Subscriber subscriber, PasswordCheck check,
                                         RadiusClient client, int interimSeconds) {
        if (check.unsupported()) {
            return Decision.reject("Login method not supported — set the NAS to PAP or CHAP");
        }
        if (!check.matches(subscriber.getPppoePassword())) {
            return Decision.reject("Wrong username or password");
        }
        if (subscriber.getStatus() != Subscriber.Status.ACTIVE) {
            // Said plainly. A suspended customer who rings up should be told
            // their account is suspended, not that they typed their password wrong.
            return Decision.reject("This account is " + subscriber.getStatus().name().toLowerCase());
        }

        List<RadiusPacket.Attribute> attributes = new ArrayList<>();
        attributes.add(RadiusPacket.number(RadiusPacket.ACCT_INTERIM_INTERVAL, interimSeconds));
        // No Session-Timeout: a monthly subscriber's access ends on a date, not
        // after a number of seconds, and sending one would drop them nightly.
        addRateLimit(attributes, client, subscriber.getBandwidth());

        return new Decision(true, "ok", attributes, null, subscriber.getId(),
                com.spalimited.hotspotbilling.domain.RadiusSession.Kind.PPPOE);
    }

    /**
     * Says "this fast" in whatever dialect the hardware understands.
     *
     * <p>There is no standard attribute for a speed limit, which is why every
     * vendor invented one. MikroTik takes the rate string it already uses;
     * WISPr's pair is understood by Ubiquiti, Cambium, Ruckus and most of the
     * rest; Cisco wants it inside an AV-pair. Sending the wrong one loses the
     * speed cap and nothing else — the customer still gets online.
     */
    private void addRateLimit(List<RadiusPacket.Attribute> attributes, RadiusClient client, String rate) {
        if (rate == null || rate.isBlank()) {
            return;
        }
        RadiusClient.Vendor vendor = client.getVendor() == null
                ? RadiusClient.Vendor.GENERIC : client.getVendor();
        switch (vendor) {
            case MIKROTIK -> attributes.add(
                    RadiusPacket.vendorText(VENDOR_MIKROTIK, MIKROTIK_RATE_LIMIT, rate));
            case CISCO -> {
                long[] bps = parseRate(rate);
                if (bps != null) {
                    attributes.add(RadiusPacket.vendorText(VENDOR_CISCO, CISCO_AVPAIR,
                            "ip:qos-policy-in=add-class(sub-qos-policy-in," + bps[0] + ")"));
                    attributes.add(RadiusPacket.vendorText(VENDOR_CISCO, CISCO_AVPAIR,
                            "ip:qos-policy-out=add-class(sub-qos-policy-out," + bps[1] + ")"));
                }
            }
            default -> {
                long[] bps = parseRate(rate);
                if (bps != null) {
                    attributes.add(RadiusPacket.vendorNumber(
                            VENDOR_WISPR, WISPR_BANDWIDTH_MAX_UP, bps[0]));
                    attributes.add(RadiusPacket.vendorNumber(
                            VENDOR_WISPR, WISPR_BANDWIDTH_MAX_DOWN, bps[1]));
                }
            }
        }
    }

    /**
     * Turns a MikroTik rate string into plain bits per second.
     *
     * <p>"5M/10M" is upload then download, in MikroTik's order. Everyone else's
     * attributes want two numbers, so the string has to be understood rather
     * than passed along.
     *
     * @return {up, down} in bits per second, or null when it cannot be read
     */
    public static long[] parseRate(String rate) {
        String[] parts = rate.trim().split("/");
        if (parts.length != 2) {
            return null;
        }
        Long up = toBps(parts[0]);
        Long down = toBps(parts[1]);
        return up == null || down == null ? null : new long[]{up, down};
    }

    private static Long toBps(String value) {
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        long multiplier = 1;
        char suffix = Character.toUpperCase(v.charAt(v.length() - 1));
        if (suffix == 'K') {
            multiplier = 1_000;
            v = v.substring(0, v.length() - 1);
        } else if (suffix == 'M') {
            multiplier = 1_000_000;
            v = v.substring(0, v.length() - 1);
        } else if (suffix == 'G') {
            multiplier = 1_000_000_000;
            v = v.substring(0, v.length() - 1);
        }
        try {
            double number = Double.parseDouble(v.trim());
            return number <= 0 ? null : (long) (number * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
