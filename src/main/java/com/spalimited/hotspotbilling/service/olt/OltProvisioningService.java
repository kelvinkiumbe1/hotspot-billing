package com.spalimited.hotspotbilling.service.olt;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authorising an ONU on an OLT, carefully.
 *
 * <p>This is the only thing in this system that writes to a device where a
 * mistake is measured in streets. A wrong SNMP OID returns nothing and a wrong
 * payment field gets a refusal; a wrong command here can deauthorise a PON port
 * and darken several hundred houses, and the OLT will do it without asking twice.
 *
 * <p>So the shape is deliberately awkward. Nothing is sent until it has been
 * shown: {@link #preview} builds the exact commands and returns them, and
 * {@link #apply} is a separate call that an operator has to make on purpose. No
 * command here touches more than one ONU. And every command that is sent is
 * recorded, in full, whether it worked or not.
 *
 * <p>None of it has been run against real hardware, because there is no OLT
 * sandbox anywhere. What the tests prove is the conversation — that the right
 * commands are built in the right order and the answers are read correctly — not
 * that a particular box agrees with them. Which is exactly why the templates are
 * editable and the preview exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OltProvisioningService {

    private final NetworkDeviceRepository devices;
    private final AuditService audit;

    /** What an operator is about to do, before they do it. */
    public record Plan(boolean possible, String reason, List<String> commands) {

        static Plan impossible(String reason) {
            return new Plan(false, reason, List.of());
        }
    }

    /** What happened when they did. */
    public record Outcome(boolean ok, String detail, List<String> transcript) {
    }

    /** An ONU the OLT has seen and nobody has authorised. */
    public record Unregistered(String serial, String frame, String slot, String port,
                               String raw) {
    }

    /** Where an ONU is going, and what to call it. */
    public record Placement(String serial, String frame, String slot, String port,
                            String onuId, String name,
                            String lineProfile, String srvProfile) {

        Map<String, String> values() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(OltDialect.SERIAL, serial);
            values.put(OltDialect.FRAME, blankTo(frame, "0"));
            values.put(OltDialect.SLOT, blankTo(slot, "0"));
            values.put(OltDialect.PORT, blankTo(port, "0"));
            values.put(OltDialect.ONU_ID, onuId);
            // A description with a space in it is a second argument on most of
            // these CLIs, which either errors or silently truncates the name.
            values.put(OltDialect.NAME, blankTo(name, "customer").replace(' ', '-'));
            values.put(OltDialect.LINE_PROFILE, blankTo(lineProfile, "1"));
            values.put(OltDialect.SRV_PROFILE, blankTo(srvProfile, "1"));
            return values;
        }

        private static String blankTo(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    // ------------------------------------------------------------- the preview

    /** The commands that authorising this ONU would send. Sends nothing. */
    @Transactional(readOnly = true)
    public Plan previewAuthorise(Long deviceId, Placement placement) {
        return plan(deviceId, placement, Action.AUTHORISE);
    }

    @Transactional(readOnly = true)
    public Plan previewDeauthorise(Long deviceId, Placement placement) {
        return plan(deviceId, placement, Action.DEAUTHORISE);
    }

    @Transactional(readOnly = true)
    public Plan previewReboot(Long deviceId, Placement placement) {
        return plan(deviceId, placement, Action.REBOOT);
    }

    private enum Action { AUTHORISE, DEAUTHORISE, REBOOT }

    private Plan plan(Long deviceId, Placement placement, Action action) {
        NetworkDevice device = devices.findById(deviceId).orElse(null);
        if (device == null || device.getKind() != NetworkDevice.Kind.OLT) {
            return Plan.impossible("That is not an OLT");
        }
        OltDialect.Dialect dialect = OltDialect.forVendor(device.getOltVendor());
        if (dialect == null) {
            return Plan.impossible("Choose the OLT vendor first — the commands differ entirely");
        }
        if (placement == null || placement.serial() == null || placement.serial().isBlank()) {
            return Plan.impossible("Which ONU? A serial is needed");
        }
        if (action != Action.AUTHORISE
                && (placement.onuId() == null || placement.onuId().isBlank())) {
            return Plan.impossible("Which ONU on the port? An ONU id is needed");
        }
        List<String> template = switch (action) {
            case AUTHORISE -> dialect.authorise();
            case DEAUTHORISE -> dialect.deauthorise();
            case REBOOT -> dialect.rebootOnu();
        };
        try {
            List<String> commands = new ArrayList<>(dialect.loginSequence());
            commands.addAll(OltDialect.fillAll(template, placement.values()));
            commands.addAll(dialect.logout());
            return new Plan(true, null, commands);
        } catch (IllegalArgumentException e) {
            // A template with a blank left in it. Reported rather than sent.
            return Plan.impossible(e.getMessage());
        }
    }

    // --------------------------------------------------------------- the doing

    /**
     * Runs a plan against the OLT.
     *
     * <p>Takes the plan rather than rebuilding it, so what is sent is exactly what
     * was shown. Rebuilding here would let the two drift, and the whole value of
     * the preview is that they cannot.
     */
    @Transactional
    public Outcome apply(Long deviceId, Plan plan, String what, String by) {
        NetworkDevice device = devices.findById(deviceId).orElse(null);
        if (device == null || !plan.possible() || plan.commands().isEmpty()) {
            return new Outcome(false, "Nothing to do", List.of());
        }
        OltDialect.Dialect dialect = OltDialect.forVendor(device.getOltVendor());
        if (dialect == null) {
            return new Outcome(false, "Choose the OLT vendor first", List.of());
        }
        // Audited before the attempt, not after. A command that hangs the session
        // still went to the OLT, and an audit trail written only on success is an
        // audit trail missing exactly the entries somebody will be looking for.
        audit.system("olt.command", by + " on " + device.getName() + ": " + what
                + " — " + String.join(" ; ", plan.commands()));

        try (OltCli cli = new OltCli(device.getHost(), cliPort(device), dialect)) {
            cli.readUntilPrompt();
            if (!login(cli, device)) {
                return new Outcome(false, "The OLT did not accept those credentials",
                        cli.transcript());
            }
            String lastError = null;
            for (String command : plan.commands()) {
                String response = cli.send(command);
                String error = errorIn(response);
                if (error != null) {
                    // Stopped at the first refusal. Carrying on through a config
                    // sequence after one command failed is how a box ends up half
                    // configured, which is worse than not configured.
                    lastError = error;
                    break;
                }
            }
            if (lastError != null) {
                return new Outcome(false, lastError, cli.transcript());
            }
            return new Outcome(true, "Done", cli.transcript());
        } catch (Exception e) {
            log.warn("OLT {} could not be reached: {}", device.getName(), e.getMessage());
            return new Outcome(false, "Could not reach the OLT: " + e.getMessage(), List.of());
        }
    }

    /** Lists the ONUs the OLT can see and nobody has authorised. Read-only. */
    @Transactional(readOnly = true)
    public List<Unregistered> unregistered(Long deviceId) {
        NetworkDevice device = devices.findById(deviceId).orElse(null);
        if (device == null || device.getKind() != NetworkDevice.Kind.OLT) {
            return List.of();
        }
        OltDialect.Dialect dialect = OltDialect.forVendor(device.getOltVendor());
        if (dialect == null) {
            return List.of();
        }
        try (OltCli cli = new OltCli(device.getHost(), cliPort(device), dialect)) {
            cli.readUntilPrompt();
            if (!login(cli, device)) {
                return List.of();
            }
            StringBuilder output = new StringBuilder();
            for (String command : dialect.loginSequence()) {
                cli.send(command);
            }
            for (String command : dialect.listUnregistered()) {
                output.append(cli.send(command));
            }
            return parseUnregistered(output.toString());
        } catch (Exception e) {
            log.warn("Could not list unregistered ONUs on {}: {}",
                    device.getName(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Pulls serials and their PON port out of whatever the OLT printed.
     *
     * <p>Deliberately loose. Every vendor lays this table out differently and
     * several change it between firmware versions, so rather than a per-vendor
     * parser that breaks on upgrade this looks for the two things every one of
     * them prints: something shaped like a GPON serial, and something shaped like
     * a frame/slot/port near it.
     *
     * <p>A serial found without a port is still returned. An operator can pick the
     * port from a dropdown; an ONU silently dropped because its line was laid out
     * unexpectedly is an installer standing in somebody's garden on the phone.
     */
    static List<Unregistered> parseUnregistered(String output) {
        List<Unregistered> found = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return found;
        }
        // A GPON serial is four ASCII vendor letters and eight hex digits;
        // EPON boxes report a MAC instead. Both are matched.
        Pattern serialPattern = Pattern.compile(
                "\\b([A-Z]{4}[0-9A-Fa-f]{8}|(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2})\\b");
        // Not \b before the first number. ZTE names a port gpon-onu_1/2/3:1 and
        // an underscore is a word character, so no word boundary ever occurs
        // before that leading 1 -- the match starts at the 2 instead, the frame is
        // lost, and slot and port shift down one. An operator would be shown the
        // wrong PON port, which on this rail is how an ONU gets authorised onto
        // somebody else's fibre. Caught by a test against the fake OLT.
        Pattern portPattern = Pattern.compile(
                "(?<![\\d.])(\\d+)\\s*/\\s*(\\d+)(?:\\s*/\\s*(\\d+))?(?![\\d.])");

        for (String line : output.split("\\r?\\n")) {
            Matcher serial = serialPattern.matcher(line);
            if (!serial.find()) {
                continue;
            }
            String frame = null;
            String slot = null;
            String port = null;
            Matcher place = portPattern.matcher(line);
            if (place.find()) {
                frame = place.group(1);
                slot = place.group(2);
                port = place.group(3);
                if (port == null) {
                    // Two numbers means slot/port on the boxes that have one
                    // frame, which is most of them.
                    port = slot;
                    slot = frame;
                    frame = "0";
                }
            }
            found.add(new Unregistered(serial.group(1), frame, slot, port, line.trim()));
        }
        return found;
    }

    /**
     * Whether a response is the OLT saying no.
     *
     * <p>These CLIs do not use exit codes. A refusal is a line of prose, and the
     * prose differs per vendor — so this looks for the handful of words all of
     * them use. A refusal read as success is a customer told they are connected
     * when the OLT never authorised them.
     */
    static String errorIn(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String lower = response.toLowerCase(java.util.Locale.ROOT);
        for (String marker : new String[]{
                "% unknown command", "unknown command", "invalid input", "% invalid",
                "failure", "error:", "% error", "command is not supported",
                "parameter error", "operation failed", "does not exist", "already exists"}) {
            int at = lower.indexOf(marker);
            if (at >= 0) {
                // The OLT's own line, which is what an operator needs, rather than
                // a message of ours that discards it.
                int lineStart = response.lastIndexOf('\n', at) + 1;
                int lineEnd = response.indexOf('\n', at);
                return response.substring(lineStart, lineEnd < 0 ? response.length() : lineEnd)
                        .trim();
            }
        }
        return null;
    }

    private boolean login(OltCli cli, NetworkDevice device) throws java.io.IOException {
        String username = device.getCliUsername();
        if (username == null || username.isBlank()) {
            // Some boxes drop straight to a prompt on a management VLAN.
            return true;
        }
        String first = cli.send(username);
        if (device.getCliPassword() != null && !device.getCliPassword().isBlank()) {
            first = cli.send(device.getCliPassword());
        }
        String lower = first.toLowerCase(java.util.Locale.ROOT);
        return !(lower.contains("incorrect") || lower.contains("failed")
                || lower.contains("denied") || lower.contains("invalid password"));
    }

    private static int cliPort(NetworkDevice device) {
        return device.getCliPort() == null || device.getCliPort() <= 0 ? 23 : device.getCliPort();
    }
}
