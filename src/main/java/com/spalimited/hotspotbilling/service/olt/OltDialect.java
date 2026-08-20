package com.spalimited.hotspotbilling.service.olt;

import com.spalimited.hotspotbilling.domain.NetworkDevice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to type at an OLT, per vendor.
 *
 * <p>Everything else in this codebase talks to an API. This types at a command
 * line and reads back what scrolls past, because that is the only interface these
 * boxes offer for provisioning — and every vendor's is different in wording,
 * in modes, and in what it does when it disagrees with you.
 *
 * <h2>Read this before changing anything here</h2>
 *
 * <p>The blast radius is unlike anything else in this system. A wrong SNMP OID
 * returns nothing. A wrong payment field gets a refusal. A wrong command on an
 * OLT can deauthorise a PON port and take several hundred houses offline at
 * once, and the OLT will do it without asking.
 *
 * <p>None of these commands has been run against real hardware — there is no
 * sandbox for an OLT and no way to get one. So three things follow, and all three
 * are deliberate:
 *
 * <ul>
 *   <li>Every template is overridable per device, like the SNMP OIDs. An operator
 *       with a console cable knows their own box better than this file does.</li>
 *   <li>Nothing is sent without being shown first. See
 *       {@code OltProvisioningService#preview} — the default is to print the exact
 *       commands and send nothing.</li>
 *   <li>There is no "deauthorise everything", no wildcard, and no command here
 *       that operates on more than one ONU. If somebody needs that they can type
 *       it themselves, having thought about it.</li>
 * </ul>
 */
public final class OltDialect {

    /**
     * One vendor's command line.
     *
     * <p>{@code prompts} are the strings that mean "your turn". Reading a CLI is
     * mostly waiting for one of these, and a missing one means every command
     * times out — which is why they are a list rather than a regex nobody can
     * debug.
     */
    public record Dialect(
            List<String> prompts,
            String moreMarker,
            List<String> loginSequence,
            List<String> listUnregistered,
            List<String> authorise,
            List<String> deauthorise,
            List<String> rebootOnu,
            List<String> logout) {
    }

    /**
     * The placeholders a template may use.
     *
     * <p>Named rather than positional because a command with the frame and slot
     * the wrong way round is a command aimed at the wrong PON port, and positional
     * arguments make that a typo rather than a compile error.
     */
    public static final String SERIAL = "{serial}";
    public static final String FRAME = "{frame}";
    public static final String SLOT = "{slot}";
    public static final String PORT = "{port}";
    public static final String ONU_ID = "{onuId}";
    public static final String NAME = "{name}";
    public static final String LINE_PROFILE = "{lineProfile}";
    public static final String SRV_PROFILE = "{srvProfile}";

    private static final Map<NetworkDevice.OltVendor, Dialect> DIALECTS = new LinkedHashMap<>();

    static {
        // Huawei MA5600 / MA5800. The commonest OLT in African fibre networks, so
        // the one most likely to be right -- and still unverified.
        DIALECTS.put(NetworkDevice.OltVendor.HUAWEI, new Dialect(
                List.of(">", "#"),
                "---- More",
                List.of("enable", "config"),
                // "display ont autofind all" lists ONUs that have been seen but
                // never authorised: exactly the box an installer just plugged in.
                List.of("display ont autofind all"),
                List.of("interface gpon " + FRAME + "/" + SLOT,
                        "ont add " + PORT + " " + ONU_ID + " sn-auth " + SERIAL
                                + " omci ont-lineprofile-id " + LINE_PROFILE
                                + " ont-srvprofile-id " + SRV_PROFILE + " desc " + NAME,
                        "quit"),
                List.of("interface gpon " + FRAME + "/" + SLOT,
                        "ont delete " + PORT + " " + ONU_ID,
                        "quit"),
                List.of("interface gpon " + FRAME + "/" + SLOT,
                        "ont reset " + PORT + " " + ONU_ID,
                        "quit"),
                List.of("quit", "quit")));

        // ZTE C300 / C320.
        DIALECTS.put(NetworkDevice.OltVendor.ZTE, new Dialect(
                List.of(">", "#"),
                "--More--",
                List.of("enable", "configure terminal"),
                List.of("show pon onu uncfg"),
                List.of("interface gpon-olt_" + FRAME + "/" + SLOT + "/" + PORT,
                        "onu " + ONU_ID + " type ZTE-F660 sn " + SERIAL,
                        "exit",
                        "interface gpon-onu_" + FRAME + "/" + SLOT + "/" + PORT + ":" + ONU_ID,
                        "name " + NAME,
                        "exit"),
                List.of("interface gpon-olt_" + FRAME + "/" + SLOT + "/" + PORT,
                        "no onu " + ONU_ID,
                        "exit"),
                List.of("pon-onu-mng gpon-onu_" + FRAME + "/" + SLOT + "/" + PORT + ":" + ONU_ID,
                        "reboot",
                        "exit"),
                List.of("exit", "exit")));

        // VSOL and the BDCOM-derived boxes sold under many house brands. Their
        // CLIs are close enough to each other to share a shape and different
        // enough that the overrides matter more here than anywhere.
        DIALECTS.put(NetworkDevice.OltVendor.VSOL, new Dialect(
                List.of(">", "#"),
                "--More--",
                List.of("enable", "config"),
                List.of("show gpon onu-information unauthorized"),
                List.of("interface gpon " + FRAME + "/" + PORT,
                        "onu add sn " + SERIAL + " onuid " + ONU_ID + " description " + NAME,
                        "exit"),
                List.of("interface gpon " + FRAME + "/" + PORT,
                        "no onu " + ONU_ID,
                        "exit"),
                List.of("interface gpon " + FRAME + "/" + PORT,
                        "onu reboot " + ONU_ID,
                        "exit"),
                List.of("exit", "exit")));

        DIALECTS.put(NetworkDevice.OltVendor.BDCOM, new Dialect(
                List.of(">", "#"),
                "--More--",
                List.of("enable", "config"),
                List.of("show epon unregistered-onu"),
                List.of("interface epon " + FRAME + "/" + PORT,
                        "epon bind-onu mac " + SERIAL + " " + ONU_ID,
                        "exit"),
                List.of("interface epon " + FRAME + "/" + PORT,
                        "no epon bind-onu " + ONU_ID,
                        "exit"),
                List.of("interface epon " + FRAME + "/" + PORT,
                        "epon onu-reboot " + ONU_ID,
                        "exit"),
                List.of("exit", "exit")));

        DIALECTS.put(NetworkDevice.OltVendor.FIBERHOME, new Dialect(
                List.of(">", "#"),
                "--More--",
                List.of("enable", "config"),
                List.of("show authorization slot " + SLOT + " pon " + PORT),
                List.of("interface pon " + SLOT + "/" + PORT,
                        "onu add " + ONU_ID + " type ONU sn " + SERIAL,
                        "quit"),
                List.of("interface pon " + SLOT + "/" + PORT,
                        "no onu " + ONU_ID,
                        "quit"),
                List.of("interface pon " + SLOT + "/" + PORT,
                        "onu reboot " + ONU_ID,
                        "quit"),
                List.of("quit", "quit")));
    }

    private OltDialect() {
    }

    public static Dialect forVendor(NetworkDevice.OltVendor vendor) {
        return vendor == null ? null : DIALECTS.get(vendor);
    }

    /**
     * Fills a template in, and refuses to produce a half-filled one.
     *
     * <p>An unreplaced placeholder would go to the OLT literally — {@code ont add
     * {port} 3 sn-auth ...} — and depending on the vendor that is either a syntax
     * error or, worse, parsed as something else. So a template with a placeholder
     * left in it is not sent at all.
     */
    public static String fill(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        if (out.contains("{") && out.contains("}")) {
            throw new IllegalArgumentException(
                    "This command still has a blank in it and was not sent: " + out);
        }
        return out;
    }

    /** Every command in a sequence, filled in. */
    public static List<String> fillAll(List<String> templates, Map<String, String> values) {
        List<String> out = new java.util.ArrayList<>();
        for (String template : templates) {
            out.add(fill(template, values));
        }
        return out;
    }
}
