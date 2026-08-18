package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.*;
import com.spalimited.hotspotbilling.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Works out who a message should go to. Segments are computed from current
 * subscriber and voucher state each time rather than stored, so a list is
 * never stale by the time it is used.
 */
@Service
@RequiredArgsConstructor
public class AudienceService {

    private final SubscriberRepository subscribers;
    private final VoucherRepository vouchers;
    private final PaymentRepository payments;
    private final com.spalimited.hotspotbilling.service.i18n.PhoneNumbers phones;

    /** A subscriber seen on the router this recently counts as online. */
    private static final Duration ONLINE_WINDOW = Duration.ofMinutes(10);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    /** One person to message, with the values their message can use. */
    public record Recipient(String phone, String name, Map<String, String> variables) {
    }

    public static final List<String> SEGMENTS = List.of(
            "online_users", "offline_users",
            "subscribed_users", "hotspot_users",
            "pppoe_users", "paused_users",
            "expired_users", "expired_pppoe_users",
            "expired_hotspot_users", "active_subscribers",
            "inactive_subscribers");

    /** Human labels, so the API and the UI cannot disagree about wording. */
    public static String label(String segment) {
        return switch (segment) {
            case "online_users" -> "Online users";
            case "offline_users" -> "Offline users";
            case "subscribed_users" -> "Subscribed users";
            case "hotspot_users" -> "Hotspot users";
            case "pppoe_users" -> "PPPoE users";
            case "paused_users" -> "Paused users";
            case "expired_users" -> "Expired users";
            case "expired_pppoe_users" -> "Expired PPPoE users";
            case "expired_hotspot_users" -> "Expired hotspot users";
            case "active_subscribers" -> "Active subscribers";
            case "inactive_subscribers" -> "Inactive subscribers";
            default -> segment;
        };
    }

    private boolean online(Subscriber s) {
        return s.getLastSeenOnlineAt() != null
                && s.getLastSeenOnlineAt().isAfter(Instant.now().minus(ONLINE_WINDOW));
    }

    private boolean lapsed(Subscriber s) {
        return s.getPaidUntil() == null || s.getPaidUntil().isBefore(Instant.now());
    }

    /** Turns a subscriber into a recipient, filling the personalisation values. */
    private Recipient toRecipient(Subscriber s) {
        Map<String, String> vars = new HashMap<>();
        String full = s.getFullName() == null ? "" : s.getFullName().trim();
        int space = full.indexOf(' ');
        vars.put("first_name", space > 0 ? full.substring(0, space) : full);
        vars.put("last_name", space > 0 ? full.substring(space + 1) : "");
        vars.put("phone", s.getPhoneNumber());
        vars.put("package_name", s.getBandwidth() == null ? "your package" : s.getBandwidth());
        vars.put("expiry_date", s.getPaidUntil() == null ? "unknown" : DATE.format(s.getPaidUntil()));
        vars.put("expiry_at", s.getPaidUntil() == null ? "unknown" : DATE.format(s.getPaidUntil()));
        return new Recipient(s.getPhoneNumber(), full, vars);
    }

    /**
     * Hotspot customers have no account, so their details come from the
     * voucher or payment that carried their number.
     */
    private Recipient hotspotRecipient(String phone, String packageName) {
        Map<String, String> vars = new HashMap<>();
        vars.put("first_name", "there");
        vars.put("last_name", "");
        vars.put("phone", phone);
        vars.put("package_name", packageName == null ? "your package" : packageName);
        vars.put("expiry_date", "unknown");
        vars.put("expiry_at", "unknown");
        return new Recipient(phone, null, vars);
    }

    @Transactional(readOnly = true)
    public List<Recipient> forSegment(String segment) {
        List<Subscriber> all = subscribers.findAll();
        return switch (segment) {
            case "online_users" -> all.stream().filter(this::online).map(this::toRecipient).toList();
            case "offline_users" -> all.stream().filter(s -> !online(s)).map(this::toRecipient).toList();
            case "subscribed_users", "pppoe_users" -> all.stream().map(this::toRecipient).toList();
            case "active_subscribers" -> all.stream()
                    .filter(s -> s.getStatus() == Subscriber.Status.ACTIVE)
                    .map(this::toRecipient).toList();
            case "inactive_subscribers", "paused_users" -> all.stream()
                    .filter(s -> s.getStatus() == Subscriber.Status.SUSPENDED)
                    .map(this::toRecipient).toList();
            case "expired_pppoe_users", "expired_users" -> all.stream()
                    .filter(this::lapsed).map(this::toRecipient).toList();
            case "hotspot_users" -> hotspotCustomers(false);
            case "expired_hotspot_users" -> hotspotCustomers(true);
            default -> throw new IllegalArgumentException("Unknown segment: " + segment);
        };
    }

    /**
     * Everyone who has ever bought hotspot access. Phone numbers come from
     * vouchers and payments; a number appears once even if it did both.
     */
    private List<Recipient> hotspotCustomers(boolean onlyFinished) {
        Map<String, String> byPhone = new LinkedHashMap<>();
        for (Voucher v : vouchers.findAll()) {
            if (v.getPhoneNumber() == null || !valid(v.getPhoneNumber())) {
                continue;
            }
            if (onlyFinished && v.getStatus() != Voucher.Status.EXPIRED) {
                continue;
            }
            byPhone.putIfAbsent(v.getPhoneNumber(), v.getPlan() == null ? null : v.getPlan().getName());
        }
        if (!onlyFinished) {
            for (Payment p : payments.findAll()) {
                if (p.getPhoneNumber() == null || !valid(p.getPhoneNumber())) {
                    continue;
                }
                byPhone.putIfAbsent(p.getPhoneNumber(), p.getPlan() == null ? null : p.getPlan().getName());
            }
        }
        return byPhone.entrySet().stream()
                .map(e -> hotspotRecipient(e.getKey(), e.getValue()))
                .toList();
    }

    /** Everyone reachable — subscribers plus past hotspot buyers. */
    @Transactional(readOnly = true)
    public List<Recipient> everyone() {
        return dedupe(concat(forSegment("subscribed_users"), hotspotCustomers(false)));
    }

    /** Subscribers on a given router, for a "this area is down" message. */
    @Transactional(readOnly = true)
    public List<Recipient> forRouter(Long routerId) {
        return subscribers.findAll().stream()
                .filter(s -> routerId.equals(s.getRouterId()))
                .map(this::toRecipient)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Recipient> forPhones(Collection<String> phones) {
        Map<String, Recipient> known = new HashMap<>();
        subscribers.findAll().forEach(s -> known.put(s.getPhoneNumber(), toRecipient(s)));
        List<Recipient> out = new ArrayList<>();
        for (String raw : phones) {
            String phone = normalise(raw);
            if (!valid(phone)) {
                continue;
            }
            out.add(known.getOrDefault(phone, hotspotRecipient(phone, null)));
        }
        return dedupe(out);
    }

    /** Counts per segment, for the audience picker. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> segmentCounts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String segment : SEGMENTS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", segment);
            row.put("label", label(segment));
            row.put("count", dedupe(forSegment(segment)).size());
            out.add(row);
        }
        return out;
    }

    /** Substitutes @variables, leaving anything unknown visibly untouched. */
    public String personalise(String body, Recipient recipient) {
        String out = body;
        for (Map.Entry<String, String> entry : recipient.variables().entrySet()) {
            out = out.replace("@" + entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    /** One message per phone number, even when segments overlap. */
    public List<Recipient> dedupe(Collection<Recipient> recipients) {
        Map<String, Recipient> byPhone = new LinkedHashMap<>();
        for (Recipient r : recipients) {
            if (r.phone() != null && valid(r.phone())) {
                byPhone.putIfAbsent(r.phone(), r);
            }
        }
        return new ArrayList<>(byPhone.values());
    }

    private static List<Recipient> concat(List<Recipient> a, List<Recipient> b) {
        List<Recipient> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean valid(String phone) {
        return phone != null && phone.matches("254\\d{9}");
    }

    /** Accepts 07..., +2547... and 2547... and settles on 2547XXXXXXXX. */
    /**
     * One canonical form for a number, whatever shape it was typed in.
     *
     * <p>Was a private copy of a Kenyan normaliser hardcoding "254" — one of
     * five identical copies, and the reason a Ghanaian ISP could configure
     * everything correctly and still take no money.
     */
    public String normalise(String raw) {
        return phones.normalise(raw);
    }
}
