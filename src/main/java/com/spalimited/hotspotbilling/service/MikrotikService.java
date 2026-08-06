package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.MikrotikProperties;
import com.spalimited.hotspotbilling.domain.MikrotikSettings;
import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.repository.MikrotikSettingsRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.legrange.mikrotik.ApiConnection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the MikroTik RouterOS API to create and remove hotspot users.
 * Each voucher becomes a hotspot user whose username and password are the
 * voucher code, so customers log in with a single code.
 *
 * Connection settings live in the database (editable from the admin
 * Settings page) and are seeded from application.properties on first use.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MikrotikService {

    private final MikrotikProperties props;
    private final MikrotikSettingsRepository settingsRepository;
    private final VoucherRepository voucherRepository;

    /** Current settings, creating the row from application.properties defaults if missing. */
    public MikrotikSettings settings() {
        return settingsRepository.findById(MikrotikSettings.SINGLETON_ID)
                .orElseGet(() -> settingsRepository.save(MikrotikSettings.builder()
                        .id(MikrotikSettings.SINGLETON_ID)
                        .enabled(props.enabled())
                        .host(props.host())
                        .port(props.port() > 0 ? props.port() : 8728)
                        .username(props.username())
                        .password(props.password())
                        .useSsl(false)
                        .build()));
    }

    public MikrotikSettings updateSettings(MikrotikSettings updated) {
        updated.setId(MikrotikSettings.SINGLETON_ID);
        return settingsRepository.save(updated);
    }

    /**
     * Opens a connection with the given settings and logs in. Throws with a
     * human-readable message on failure; used by the admin "Test Connection"
     * button before saving.
     */
    public void testConnection(MikrotikSettings s) {
        try (ApiConnection connection = open(s)) {
            connection.login(s.getUsername(), s.getPassword());
        } catch (Exception e) {
            throw new IllegalStateException("Connection failed: " + e.getMessage(), e);
        }
    }

    public void provisionVoucher(Voucher voucher) {
        MikrotikSettings s = settings();
        if (!s.isEnabled()) {
            log.info("MikroTik disabled - skipping provisioning of voucher {}", voucher.getCode());
            return;
        }
        String limitUptime = voucher.getEffectiveDurationMinutes() + "m";
        try (ApiConnection connection = open(s)) {
            connection.login(s.getUsername(), s.getPassword());
            String profile = ensureProfile(connection, voucher.getPlan());
            connection.execute(String.format(
                    "/ip/hotspot/user/add name=%s password=%s profile=%s limit-uptime=%s",
                    voucher.getCode(), voucher.getCode(), profile, limitUptime));
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Provisioned hotspot user for voucher {}", voucher.getCode());
    }

    /**
     * Returns the hotspot user profile for the plan. If the admin named an
     * existing router profile on the plan, that wins; otherwise a profile
     * is auto-managed per plan carrying the bandwidth rate limit and the
     * shared-users device cap (this is what stops one voucher being used
     * on several devices at once — RouterOS rejects logins beyond the cap).
     */
    private String ensureProfile(ApiConnection connection, com.spalimited.hotspotbilling.domain.Plan plan) throws Exception {
        if (plan.getMikrotikProfile() != null && !plan.getMikrotikProfile().isBlank()) {
            return plan.getMikrotikProfile();
        }
        String name = "spa-plan-" + plan.getId();
        String rateLimit = plan.getBandwidth() != null && !plan.getBandwidth().isBlank()
                ? " rate-limit=" + plan.getBandwidth() : "";
        int sharedUsers = plan.getEffectiveMaxDevices();
        try {
            connection.execute(String.format(
                    "/ip/hotspot/user/profile/add name=%s shared-users=%d%s", name, sharedUsers, rateLimit));
        } catch (Exception alreadyExists) {
            connection.execute(String.format(
                    "/ip/hotspot/user/profile/set [find name=%s] shared-users=%d%s", name, sharedUsers, rateLimit));
        }
        return name;
    }

    public void removeVoucher(Voucher voucher) {
        MikrotikSettings s = settings();
        if (!s.isEnabled()) {
            return;
        }
        try (ApiConnection connection = open(s)) {
            connection.login(s.getUsername(), s.getPassword());
            // Kick any live session first so the device drops immediately.
            try {
                connection.execute("/ip/hotspot/active/remove [find user=" + voucher.getCode() + "]");
            } catch (Exception noActiveSession) {
                log.debug("No active session to kick for {}", voucher.getCode());
            }
            connection.execute("/ip/hotspot/user/remove [find name=" + voucher.getCode() + "]");
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
        log.info("Removed hotspot user for voucher {}", voucher.getCode());
    }

    /**
     * MAC binding: locks every voucher to the first device that uses it.
     * Reads the router's active hotspot sessions and, for any logged-in
     * user whose router record has no mac-address yet, writes the session
     * MAC onto the user. RouterOS then rejects logins from other devices.
     * Runs from MacBindingJob every minute while the feature is enabled.
     */
    @Transactional
    public void syncMacBindings() {
        MikrotikSettings s = settings();
        if (!s.isEnabled() || !s.isMacBindingEnabled()) {
            return;
        }
        try (ApiConnection connection = open(s)) {
            connection.login(s.getUsername(), s.getPassword());

            Map<String, String> userMacs = new HashMap<>();
            List<Map<String, String>> users = connection.execute("/ip/hotspot/user/print");
            for (Map<String, String> user : users) {
                userMacs.put(user.get("name"), user.getOrDefault("mac-address", ""));
            }

            List<Map<String, String>> sessions = connection.execute("/ip/hotspot/active/print");
            for (Map<String, String> session : sessions) {
                String user = session.get("user");
                String mac = session.get("mac-address");
                if (user == null || mac == null || mac.isBlank()) {
                    continue;
                }
                String existing = userMacs.get(user);
                if (existing == null || !existing.isBlank()) {
                    continue; // unknown user, or already bound
                }
                connection.execute(String.format(
                        "/ip/hotspot/user/set [find name=%s] mac-address=%s", user, mac));
                voucherRepository.findByCode(user).ifPresent(v -> {
                    v.setBoundMac(mac);
                    voucherRepository.save(v);
                });
                log.info("MAC-bound voucher {} to {}", user, mac);
            }
        } catch (Exception e) {
            throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
        }
    }

    /** Clears the MAC lock so the customer can use the voucher on a different device. */
    @Transactional
    public void unbindVoucher(Voucher voucher) {
        MikrotikSettings s = settings();
        if (s.isEnabled()) {
            try (ApiConnection connection = open(s)) {
                connection.login(s.getUsername(), s.getPassword());
                connection.execute(String.format(
                        "/ip/hotspot/user/unset [find name=%s] value-name=mac-address", voucher.getCode()));
            } catch (Exception e) {
                throw new IllegalStateException("MikroTik API call failed: " + e.getMessage(), e);
            }
        }
        voucher.setBoundMac(null);
    }

    private ApiConnection open(MikrotikSettings s) throws Exception {
        SocketFactory factory = s.isUseSsl() ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        int port = s.getPort() > 0 ? s.getPort() : (s.isUseSsl() ? 8729 : 8728);
        return ApiConnection.connect(factory, s.getHost(), port, ApiConnection.DEFAULT_CONNECTION_TIMEOUT);
    }
}
