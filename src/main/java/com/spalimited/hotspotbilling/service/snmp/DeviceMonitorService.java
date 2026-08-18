package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.DeviceInterface;
import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.repository.DeviceInterfaceRepository;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.OperatorAlertSettingsService;
import com.spalimited.hotspotbilling.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polls every device that isn't a MikroTik and works out what changed.
 *
 * <p>The point is not the poll, it is the comparison. A switch that answers
 * tells you nothing on its own; a switch whose uplink dropped from 1G to 100M
 * since the last poll, or whose error counter has moved for the first time in
 * six months, is the fault you get to fix before a customer notices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceMonitorService {

    /**
     * Errors in one poll before it is worth saying something. A handful on a
     * busy port is normal; this many is a cable, an SFP or a duplex mismatch.
     */
    private static final long ERROR_ALERT_THRESHOLD = 100;

    /** A wrap-corrected delta bigger than this multiple of the link speed is a lie. */
    private static final double PLAUSIBILITY_MARGIN = 1.5;

    private static final long COUNTER_32_BIT = 1L << 32;

    private final NetworkDeviceRepository devices;
    private final DeviceInterfaceRepository interfaces;
    private final SnmpClient snmp;
    private final AuditService audit;
    private final SmsService smsService;
    private final MessagingSettingsService messagingSettings;
    private final OperatorAlertSettingsService alertSettings;

    /** What one poll changed, for the admin to show after a manual "check now". */
    public record PollResult(boolean online, String error, int portsSeen, int portsUp) {
    }

    /**
     * Polls one device and records everything learned.
     *
     * <p>Runs in its own transaction so one device that times out cannot roll
     * back what the previous fifteen just recorded.
     */
    @Transactional
    public PollResult poll(Long deviceId) {
        NetworkDevice device = devices.findById(deviceId).orElseThrow(() ->
                new IllegalArgumentException("No such device"));
        return poll(device);
    }

    @Transactional
    public PollResult poll(NetworkDevice device) {
        boolean wasOnline = device.isOnline();
        Long previousUptime = device.getUptimeSeconds();
        Instant now = Instant.now();

        SnmpClient.Probe probe = snmp.probe(device);
        device.setLastCheckedAt(now);
        device.setOnline(probe.reachable());

        if (!probe.reachable()) {
            device.setLastError(probe.error());
            devices.save(device);
            if (wasOnline) {
                announce(device, false, probe.error());
            }
            return new PollResult(false, probe.error(), 0, 0);
        }

        device.setLastSeenAt(now);
        device.setLastError(null);
        device.setSysName(probe.sysName());
        device.setSysDescr(probe.sysDescr());
        device.setSysLocation(probe.sysLocation());
        device.setSysContact(probe.sysContact());

        // Uptime going backwards is the only evidence we ever get of a reboot
        // that happened and recovered between two polls. Without it, a device
        // rebooting nightly at 3am reads as perfect uptime.
        if (probe.uptimeSeconds() != null) {
            if (previousUptime != null && probe.uptimeSeconds() < previousUptime) {
                device.setLastRebootAt(now);
                audit.system("device.reboot", device.getName() + " restarted (uptime went from "
                        + humanUptime(previousUptime) + " back to " + humanUptime(probe.uptimeSeconds()) + ")");
            }
            device.setUptimeSeconds(probe.uptimeSeconds());
        }
        devices.save(device);

        if (!wasOnline) {
            announce(device, true, null);
        }

        int seen = 0;
        int up = 0;
        try {
            List<SnmpClient.Port> ports = snmp.ports(device);
            seen = ports.size();
            up = (int) ports.stream().filter(SnmpClient.Port::operUp).count();
            recordPorts(device, ports, now);
        } catch (Exception e) {
            // The device answered sysName, so it is up. Failing to read the
            // interface table is a smaller problem and must not be reported as
            // an outage — that is the false alarm that trains people to ignore alarms.
            log.debug("Interface table unavailable on {}: {}", device.getName(), e.getMessage());
        }
        return new PollResult(true, null, seen, up);
    }

    /**
     * Folds one poll's port readings into what we already had, and says
     * something when a port an operator asked to be told about goes down.
     */
    private void recordPorts(NetworkDevice device, List<SnmpClient.Port> ports, Instant now) {
        Set<Integer> present = new HashSet<>();
        List<String> lost = new ArrayList<>();
        List<String> errored = new ArrayList<>();

        for (SnmpClient.Port port : ports) {
            present.add(port.ifIndex());
            DeviceInterface row = interfaces
                    .findByDeviceIdAndIfIndex(device.getId(), port.ifIndex())
                    .orElseGet(() -> DeviceInterface.builder()
                            .deviceId(device.getId()).ifIndex(port.ifIndex()).build());

            boolean wasUp = row.isOperUp();
            boolean firstSighting = row.getId() == null;

            row.setIfName(port.name());
            row.setIfAlias(port.alias());
            row.setIfDescr(port.descr());
            row.setAdminUp(port.adminUp());
            row.setOperUp(port.operUp());
            if (port.speedBps() != null) {
                row.setSpeedBps(port.speedBps());
            }
            if (wasUp != port.operUp() && !firstSighting) {
                row.setLastChangeAt(now);
            }

            applyCounters(row, port, now);

            interfaces.save(row);

            if (!row.isMonitored() || firstSighting) {
                continue;
            }
            if (wasUp && !port.operUp()) {
                lost.add(row.getLabel());
            }
            if (row.getInErrorsDelta() + row.getOutErrorsDelta() >= ERROR_ALERT_THRESHOLD) {
                errored.add(row.getLabel() + " (" + (row.getInErrorsDelta() + row.getOutErrorsDelta()) + ")");
            }
        }

        // A port that has vanished from the table entirely — a stacked switch
        // member that dropped out, or a module pulled. Kept rather than deleted:
        // its history is the evidence of what was there before.
        for (DeviceInterface stale : interfaces.findByDeviceIdOrderByIfIndexAsc(device.getId())) {
            if (present.contains(stale.getIfIndex()) || !stale.isOperUp()) {
                continue;
            }
            stale.setOperUp(false);
            stale.setInBps(null);
            stale.setOutBps(null);
            stale.setLastChangeAt(now);
            interfaces.save(stale);
            if (stale.isMonitored()) {
                lost.add(stale.getLabel() + " (no longer reported)");
            }
        }

        if (!lost.isEmpty()) {
            String detail = device.getName() + ": " + String.join(", ", lost) + " went down";
            audit.system("device.port.down", detail);
            notifyOperator("ALERT: " + detail);
        }
        if (!errored.isEmpty()) {
            String detail = device.getName() + ": errors climbing on " + String.join(", ", errored)
                    + " — usually a cable, an SFP or a duplex mismatch";
            audit.system("device.port.errors", detail);
            notifyOperator("ALERT: " + detail);
        }
    }

    /**
     * Turns two cumulative readings into a rate.
     *
     * <p>Counters only ever climb, so a reading lower than the last one means
     * one of two things: a 32-bit counter wrapped, or the device restarted and
     * cleared them. Telling those apart matters — guessing "wrapped" after a
     * reboot invents four gigabytes of traffic that never happened, and every
     * capacity decision downstream is then made on a fiction.
     *
     * <p>So a wrap is only assumed when the resulting figure is one the link
     * could physically have carried in the time available. Otherwise the sample
     * is dropped and only the new baseline is kept.
     */
    private void applyCounters(DeviceInterface row, SnmpClient.Port port, Instant now) {
        Long in = port.inOctets();
        Long out = port.outOctets();
        Instant previousAt = row.getCountersAt();
        Long previousIn = row.getLastInOctets();
        Long previousOut = row.getLastOutOctets();

        if (in != null) {
            row.setLastInOctets(in);
        }
        if (out != null) {
            row.setLastOutOctets(out);
        }

        long errorsIn = delta(row.getLastInErrors(), port.inErrors());
        long errorsOut = delta(row.getLastOutErrors(), port.outErrors());
        row.setInErrorsDelta(errorsIn);
        row.setOutErrorsDelta(errorsOut);
        if (port.inErrors() != null) {
            row.setLastInErrors(port.inErrors());
        }
        if (port.outErrors() != null) {
            row.setLastOutErrors(port.outErrors());
        }

        if (in == null || out == null) {
            return;
        }
        row.setCountersAt(now);

        if (previousAt == null || previousIn == null || previousOut == null) {
            return; // first sighting: a baseline, not a measurement
        }
        long seconds = Duration.between(previousAt, now).toSeconds();
        if (seconds <= 0) {
            return;
        }

        Long deltaIn = octetDelta(previousIn, in, port.sixtyFourBit(), row.getSpeedBps(), seconds);
        Long deltaOut = octetDelta(previousOut, out, port.sixtyFourBit(), row.getSpeedBps(), seconds);
        row.setInBps(deltaIn == null ? null : deltaIn * 8 / seconds);
        row.setOutBps(deltaOut == null ? null : deltaOut * 8 / seconds);
    }

    /** Null when the two readings cannot be reconciled into a believable figure. */
    static Long octetDelta(long previous, long current, boolean sixtyFourBit,
                           Long speedBps, long seconds) {
        if (current >= previous) {
            return current - previous;
        }
        if (sixtyFourBit) {
            // 64 bits is 18 exabytes. It did not wrap; the device cleared it.
            return null;
        }
        if (speedBps == null || speedBps <= 0) {
            // No link speed to check it against, so there is no way to tell a
            // wrap from a reset. Say nothing rather than guess.
            return null;
        }
        long carryable = (long) (speedBps / 8.0 * seconds);
        if (carryable >= COUNTER_32_BIT) {
            // The link could have pushed the counter past its limit more than
            // once since the last poll, so the reading cannot be reconstructed
            // at all — a single wrap correction would just be arithmetic on a
            // number that no longer means anything. This is the ordinary case
            // for a 32-bit counter on a gigabit port at a five-minute poll,
            // and it is why the 64-bit counters are preferred wherever the
            // device offers them.
            return null;
        }
        long wrapped = COUNTER_32_BIT - previous + current;
        return wrapped <= carryable * PLAUSIBILITY_MARGIN ? wrapped : null;
    }

    private static long delta(Long previous, Long current) {
        if (previous == null || current == null || current < previous) {
            return 0;
        }
        return current - previous;
    }

    private void announce(NetworkDevice device, boolean online, String error) {
        if (online) {
            audit.system("device.online", device.getName() + " is answering again");
            notifyOperator("Recovered: " + device.getName() + " (" + device.getHost() + ") is back.");
        } else {
            audit.system("device.offline", device.getName() + " stopped answering: " + error);
            notifyOperator("ALERT: " + device.getName() + " (" + device.getHost()
                    + ") has stopped answering. " + error);
        }
    }

    /** Reuses the router-offline switch: one place to turn network noise off. */
    private void notifyOperator(String message) {
        if (!alertSettings.get().isRouterOfflineAlert()) {
            return;
        }
        String phone = messagingSettings.alertPhone();
        if (phone != null && !phone.isBlank()) {
            smsService.trySend(phone, message);
        }
    }

    static String humanUptime(long seconds) {
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
