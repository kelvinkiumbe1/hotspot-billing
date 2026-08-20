package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Runtime-editable MikroTik connection settings, stored as a single row
 * (id = 1). Seeded from application.properties on first access so existing
 * deployments keep working, then managed from the admin Settings page.
 */
@Entity
@Table(name = "mikrotik_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MikrotikSettings {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    private String host;

    private int port;

    private String username;

    private String password;

    @Column(nullable = false)
    private boolean useSsl;

    /** Optional RouterOS certificate name when SSL is enabled. */
    private String certificate;

    /** Hotspot server name on the router, e.g. "hs-server1". */
    private String hotspotServer;

    /** Router interface the hotspot runs on, e.g. "bridge1" or "wlan1". */
    private String interfaceName;

    /** DNS name of the captive portal, e.g. "hotspot.spawifi.local". */
    private String dnsName;

    /**
     * Push every hotspot voucher to every managed router, so a code sold at one
     * site works at all of them.
     *
     * <p>Off by default because the cost is real: provisioning becomes one API
     * call per router instead of one, and a router that is down when a code is
     * sold will not have it until the next sweep. An operator with a single site
     * gains nothing and pays for every voucher.
     */
    @Column(name = "roaming_enabled", nullable = false)
    @Builder.Default
    private boolean roamingEnabled = false;

    /**
     * Lock each voucher to the first device that uses it (MAC binding).
     * Nullable so the column can be added to existing databases; null
     * means off.
     */
    private Boolean macBinding;

    @Transient
    public boolean isMacBindingEnabled() {
        return Boolean.TRUE.equals(macBinding);
    }
}
