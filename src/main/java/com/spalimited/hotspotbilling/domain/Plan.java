package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An internet access package a customer can buy, e.g. "1 Hour @ 20 KES".
 * Covers both hotspot vouchers and PPPoE subscriptions, with the speed,
 * fair-use and availability rules that get pushed to the router.
 */
@Entity
@Table(name = "plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan {

    /** Which side of the business the package belongs to. */
    public enum Type { HOTSPOT, PPPOE }

    /**
     * LIVE is on sale, HIDDEN still works for anyone holding a code but is
     * off the public list, OFF is withdrawn entirely.
     */
    public enum Availability { LIVE, HIDDEN, OFF }

    /** What to do once a subscriber passes the fair-use limit. */
    public enum FupAction { THROTTLE, BLOCK, NOTIFY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** How long access lasts once activated, in minutes. */
    @Column(nullable = false)
    private int durationMinutes;

    /** Optional data cap in MB; null means unlimited within the duration. */
    private Integer dataLimitMb;

    /** MikroTik rate limit string, e.g. "5M/5M" (upload/download). */
    private String bandwidth;

    /** Name of the matching MikroTik hotspot user profile. */
    private String mikrotikProfile;

    /**
     * How many devices may use one voucher at the same time (MikroTik
     * shared-users). Null or 0 means 1 device.
     */
    private Integer maxDevices;

    @Transient
    public int getEffectiveMaxDevices() {
        return maxDevices != null && maxDevices > 0 ? maxDevices : 1;
    }

    @Enumerated(EnumType.STRING)
    private Type type;

    /** Nullable on rows created before types existed; hotspot is the default. */
    @Transient
    public Type getEffectiveType() {
        return type == null ? Type.HOTSPOT : type;
    }

    @Enumerated(EnumType.STRING)
    private Availability availability;

    @Transient
    public Availability getEffectiveAvailability() {
        if (availability != null) {
            return availability;
        }
        return active ? Availability.LIVE : Availability.OFF;
    }

    /** On sale and listed on the captive portal. */
    @Transient
    public boolean isOnSale() {
        return getEffectiveAvailability() == Availability.LIVE;
    }

    /** Usable by someone who already holds a code, even if delisted. */
    @Transient
    public boolean isUsable() {
        return getEffectiveAvailability() != Availability.OFF;
    }

    // --- Burst: all three values or none, per RouterOS ---

    /** Peak rate a subscriber may briefly reach, e.g. "10M/10M". */
    private String burstLimit;

    /** Average rate below which burst is allowed, e.g. "5M/5M". */
    private String burstThreshold;

    /** Seconds the burst may last, e.g. "30/30". */
    private String burstTime;

    @Transient
    public boolean hasBurst() {
        return notBlank(burstLimit) && notBlank(burstThreshold) && notBlank(burstTime);
    }

    /**
     * The full RouterOS rate-limit string:
     * {@code rate burst-limit burst-threshold burst-time}. Burst is appended
     * only when all three parts are present, because RouterOS rejects a
     * partial burst spec.
     */
    @Transient
    public String getRateLimitString() {
        if (!notBlank(bandwidth)) {
            return null;
        }
        return hasBurst()
                ? bandwidth + " " + burstLimit + " " + burstThreshold + " " + burstTime
                : bandwidth;
    }

    // --- Fair use ---

    private Boolean fupEnabled;

    /** Monthly usage in MB that trips the fair-use action. */
    private Integer fupLimitMb;

    @Enumerated(EnumType.STRING)
    private FupAction fupAction;

    /** Rate to drop to when the action is THROTTLE, e.g. "1M/1M". */
    private String fupRate;

    @Transient
    public boolean isFupOn() {
        return Boolean.TRUE.equals(fupEnabled) && fupLimitMb != null && fupLimitMb > 0;
    }

    // --- Time-of-day schedule, for things like a night plan ---

    private Boolean scheduleEnabled;

    private LocalTime scheduleFrom;

    private LocalTime scheduleTo;

    @Transient
    public boolean isScheduled() {
        return Boolean.TRUE.equals(scheduleEnabled) && scheduleFrom != null && scheduleTo != null;
    }

    /**
     * Whether the plan may be used at the given time. A window whose end is
     * before its start is treated as crossing midnight, so 23:00–06:00 means
     * "overnight" rather than an empty range.
     */
    public boolean isUsableAt(LocalTime when) {
        if (!isScheduled()) {
            return true;
        }
        if (scheduleFrom.equals(scheduleTo)) {
            return true; // a zero-length window is meaningless; treat as always on
        }
        return scheduleFrom.isBefore(scheduleTo)
                ? !when.isBefore(scheduleFrom) && when.isBefore(scheduleTo)
                : !when.isBefore(scheduleFrom) || when.isBefore(scheduleTo);
    }

    /**
     * Routers this plan may be sold on. Empty means every router, which is
     * the right default for a single-site operation.
     */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_routers", joinColumns = @JoinColumn(name = "plan_id"))
    @Column(name = "router_id")
    private Set<Long> allowedRouterIds = new LinkedHashSet<>();

    public boolean allowsRouter(Long routerId) {
        return allowedRouterIds == null || allowedRouterIds.isEmpty()
                || routerId == null || allowedRouterIds.contains(routerId);
    }

    /**
     * Kept in step with {@link #availability} so existing queries that filter
     * on it keep working; availability is the authoritative field.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = true;

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
