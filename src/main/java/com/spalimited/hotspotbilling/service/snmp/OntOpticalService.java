package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.domain.OntReading;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.repository.OntReadingRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MessagingSettingsService;
import com.spalimited.hotspotbilling.service.OperatorAlertService;
import com.spalimited.hotspotbilling.service.OperatorAlertSettingsService;
import com.spalimited.hotspotbilling.service.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reading every ONU on every OLT, and noticing what changed.
 *
 * <p>Same shape as {@link DeviceMonitorService} and for the same reason: the poll
 * is not the point, the comparison is. A drop reading −24 dBm tells you nothing —
 * plenty of working fibres sit there. A drop that read −20 dBm at the last poll
 * and −25 now is a connector somebody disturbed, and it is worth knowing before
 * the customer notices.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OntOpticalService {

    /**
     * How long before the same failing drop is reported again.
     *
     * <p>Six hours. A fibre that has gone dark stays dark until somebody drives
     * out to it, and repeating the message every five minutes trains an operator
     * to mute the alerts — after which the next one is missed too.
     */
    private static final Duration ALERT_COOLDOWN = Duration.ofHours(6);

    private final NetworkDeviceRepository devices;
    private final OntReadingRepository readings;
    private final SnmpClient snmp;
    private final AuditService audit;
    private final SmsService smsService;
    private final OperatorAlertService operatorAlerts;
    private final MessagingSettingsService messagingSettings;
    private final OperatorAlertSettingsService alertSettings;

    /** What one sweep of an OLT found, for the admin to show after "check now". */
    public record PollResult(boolean polled, String error, int onusSeen, int alerted) {

        static PollResult skipped(String why) {
            return new PollResult(false, why, 0, 0);
        }
    }

    /** Every OLT that is switched on, worst-lit first is the caller's business. */
    @Transactional(readOnly = true)
    public List<NetworkDevice> olts() {
        return devices.findAll().stream()
                .filter(d -> d.getKind() == NetworkDevice.Kind.OLT)
                .filter(NetworkDevice::isEnabled)
                .toList();
    }

    @Transactional
    public PollResult poll(Long deviceId) {
        return devices.findById(deviceId)
                .map(this::poll)
                .orElseGet(() -> PollResult.skipped("No such device"));
    }

    /**
     * Reads one OLT and files what it said.
     *
     * <p>Never throws for an unreachable OLT. An OLT being down is a thing this
     * exists to notice, and one bad device must not stop the sweep reaching the
     * rest — the same contract {@link SnmpClient} holds.
     */
    @Transactional
    public PollResult poll(NetworkDevice device) {
        if (device.getKind() != NetworkDevice.Kind.OLT) {
            return PollResult.skipped("Not an OLT");
        }
        OltProfile.Columns columns = OltProfile.forDevice(device);
        if (columns == null) {
            // No vendor and no overrides. Said plainly rather than polled anyway:
            // walking a made-up OID reports an OLT with no ONUs, which looks like
            // a working integration finding nothing.
            return PollResult.skipped("Choose the OLT vendor, or enter the ONU OIDs, first");
        }

        List<SnmpClient.Onu> found;
        try {
            found = snmp.onus(device, columns);
        } catch (Exception e) {
            log.debug("Could not read ONUs from {}: {}", device.getName(),
                    SnmpClient.describe(e));
            return PollResult.skipped(SnmpClient.describe(e));
        }
        if (found.isEmpty()) {
            // Indistinguishable at the protocol level from a wrong OID, so the
            // message says both possibilities rather than picking one.
            return new PollResult(true,
                    "The OLT answered but reported no ONUs — check the ONU OIDs for this vendor",
                    0, 0);
        }

        Instant now = Instant.now();
        int alerted = 0;
        int stored = 0;
        for (SnmpClient.Onu onu : found) {
            if (onu.serial() == null || onu.serial().isBlank()) {
                // SnmpClient already drops these, and the guard is here as well
                // because this is where it has to hold: serial is the row's
                // identity and the column is NOT NULL, so trusting the caller
                // turns a clean skip into a failed insert that takes the rest of
                // the sweep down with it. Postgres also treats NULLs as distinct
                // in a unique constraint, so they would not even collide -- they
                // would pile up as junk rows.
                continue;
            }
            OntReading row = readings
                    .findByOltDeviceIdAndSerial(device.getId(), onu.serial())
                    .orElseGet(() -> OntReading.builder()
                            .oltDeviceId(device.getId())
                            .serial(onu.serial())
                            .build());

            Double previous = row.getRxDbm();
            // Only moved when there is a new reading to move it for. An OLT that
            // stops reporting power for one ONU must not erase the last number
            // somebody was looking at.
            if (onu.rxDbm() != null) {
                row.setPreviousRxDbm(previous);
                row.setRxDbm(onu.rxDbm());
            }
            if (onu.txDbm() != null) {
                row.setTxDbm(onu.txDbm());
            }
            row.setTableIndex(indexOf(onu.index()));
            if (onu.description() != null && !onu.description().isBlank()) {
                row.setDescription(onu.description());
            }
            if (onu.status() != null && !onu.status().isBlank()) {
                row.setStatus(onu.status());
            }
            row.setHealth(OpticalPower.health(row.getRxDbm()));
            row.setLastSeenAt(now);

            if (shouldAlert(row, previous, now)) {
                row.setLastAlertedAt(now);
                alerted++;
                announce(device, row, previous);
            }
            readings.save(row);
            stored++;
        }
        // What was stored, not what was seen: an ONU the OLT listed without a
        // serial is not an ONU this can say anything about.
        return new PollResult(true, null, stored, alerted);
    }

    /**
     * Whether this reading is worth waking somebody for.
     *
     * <p>Two conditions, and both matter. A three-decibel drop is something that
     * physically changed. A reading past the receiver's budget is a link that will
     * not stay up whether it changed or not — a drop installed badly a year ago
     * never "changes" and would otherwise never be reported.
     *
     * <p>Both are behind the cooldown, so a fibre that stays broken is mentioned
     * once every six hours rather than every five minutes.
     */
    private boolean shouldAlert(OntReading row, Double previous, Instant now) {
        boolean dropped = OpticalPower.worthAlerting(previous, row.getRxDbm());
        boolean outOfBudget = row.getHealth() == OpticalPower.Health.BAD
                || row.getHealth() == OpticalPower.Health.DOWN;
        if (!dropped && !outOfBudget) {
            return false;
        }
        Instant last = row.getLastAlertedAt();
        return last == null || last.isBefore(now.minus(ALERT_COOLDOWN));
    }

    private void announce(NetworkDevice olt, OntReading row, Double previous) {
        String who = row.getDescription() != null && !row.getDescription().isBlank()
                ? row.getDescription() : row.getSerial();
        String change = previous == null ? ""
                : String.format(" (was %.1f dBm)", previous);
        String message = String.format("Fibre: %s on %s is %.1f dBm%s — %s.",
                who, olt.getName(),
                row.getRxDbm() == null ? Double.NaN : row.getRxDbm(),
                change, plainly(row.getHealth()));
        audit.system("ont.optical", message);
        // Reuses the router-offline switch, so network noise has one off button.
        if (!alertSettings.get().isRouterOfflineAlert()) {
            return;
        }
        operatorAlerts.alert(message);
    }

    /** What to tell somebody who does not read decibels. */
    public static String plainly(OpticalPower.Health health) {
        if (health == null) {
            return "no reading";
        }
        return switch (health) {
            case GOOD -> "fine";
            case MARGINAL -> "getting marginal";
            case BAD -> "past the budget, expect drops";
            case DOWN -> "no light — the fibre is broken or unplugged";
            case TOO_HOT -> "too strong, it needs an attenuator";
            case UNKNOWN -> "no reading";
        };
    }

    /** The readings for one OLT, worst light first, which is the order to work in. */
    @Transactional(readOnly = true)
    public List<OntReading> forOlt(Long oltDeviceId) {
        List<OntReading> rows = new ArrayList<>(readings.findByOltDeviceId(oltDeviceId));
        rows.sort(worstFirst());
        return rows;
    }

    /** Everything not currently healthy, across every OLT. */
    @Transactional(readOnly = true)
    public List<OntReading> attentionNeeded() {
        List<OntReading> rows = new ArrayList<>(readings.findByHealthIn(List.of(
                OpticalPower.Health.DOWN, OpticalPower.Health.BAD,
                OpticalPower.Health.MARGINAL, OpticalPower.Health.TOO_HOT)));
        rows.sort(worstFirst());
        return rows;
    }

    /**
     * Worst first, and "worst" is the band rather than the number.
     *
     * <p>A −31 dBm link and a −28 dBm link are both broken and it does not matter
     * which is lower; a −5 dBm receiver being overloaded is a different fault
     * from either and would sort to the bottom on the number alone.
     */
    private static Comparator<OntReading> worstFirst() {
        return Comparator
                .comparingInt((OntReading r) -> severity(r.getHealth()))
                .thenComparing(r -> Optional.ofNullable(r.getRxDbm()).orElse(0.0));
    }

    private static int severity(OpticalPower.Health health) {
        if (health == null) {
            return 5;
        }
        return switch (health) {
            case DOWN -> 0;
            case BAD -> 1;
            case TOO_HOT -> 2;
            case MARGINAL -> 3;
            case UNKNOWN -> 4;
            case GOOD -> 5;
        };
    }

    /**
     * The last element of a composite SNMP index, for a technician to read.
     *
     * <p>Stored for display only. The row is keyed on the serial — see
     * {@link OntReading#getSerial()} for why the index cannot be trusted as an
     * identity.
     */
    static Integer indexOf(String index) {
        if (index == null || index.isBlank()) {
            return null;
        }
        String last = index.substring(index.lastIndexOf('.') + 1);
        try {
            return Integer.valueOf(last.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
