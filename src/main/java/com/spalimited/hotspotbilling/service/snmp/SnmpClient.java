package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.UserTarget;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.*;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.snmp4j.util.DefaultPDUFactory;
import org.snmp4j.util.TableEvent;
import org.snmp4j.util.TableUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Talks SNMP to one device at a time.
 *
 * <p>A session is opened per call rather than kept alive. SNMP over UDP has no
 * connection to reuse, and the alternative — one long-lived engine shared by
 * every device — means one unreachable box can hold a lock while the rest of
 * the poll waits behind it.
 *
 * <p>Nothing here throws for an unreachable device. A device being down is the
 * normal case this exists to detect, not an error condition; it comes back as
 * an empty result with a reason attached.
 */
@Component
@Slf4j
public class SnmpClient {

    // --- SNMPv2-MIB: what every device answers ---
    static final String SYS_DESCR = "1.3.6.1.2.1.1.1.0";
    static final String SYS_UPTIME = "1.3.6.1.2.1.1.3.0";
    static final String SYS_CONTACT = "1.3.6.1.2.1.1.4.0";
    static final String SYS_NAME = "1.3.6.1.2.1.1.5.0";
    static final String SYS_LOCATION = "1.3.6.1.2.1.1.6.0";

    // --- IF-MIB: the 32-bit original table ---
    static final String IF_DESCR = "1.3.6.1.2.1.2.2.1.2";
    static final String IF_SPEED = "1.3.6.1.2.1.2.2.1.5";
    static final String IF_ADMIN_STATUS = "1.3.6.1.2.1.2.2.1.7";
    static final String IF_OPER_STATUS = "1.3.6.1.2.1.2.2.1.8";
    static final String IF_IN_OCTETS = "1.3.6.1.2.1.2.2.1.10";
    static final String IF_IN_ERRORS = "1.3.6.1.2.1.2.2.1.14";
    static final String IF_OUT_OCTETS = "1.3.6.1.2.1.2.2.1.16";
    static final String IF_OUT_ERRORS = "1.3.6.1.2.1.2.2.1.20";

    // --- IF-MIB ifXTable: 64-bit counters and the human-typed port label ---
    static final String IF_NAME = "1.3.6.1.2.1.31.1.1.1.1";
    static final String IF_HC_IN_OCTETS = "1.3.6.1.2.1.31.1.1.1.6";
    static final String IF_HC_OUT_OCTETS = "1.3.6.1.2.1.31.1.1.1.10";
    static final String IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15";
    static final String IF_ALIAS = "1.3.6.1.2.1.31.1.1.1.18";

    private static final int TIMEOUT_MS = 3_000;
    private static final int RETRIES = 1;

    /** What one device said about itself, or why it said nothing. */
    public record Probe(boolean reachable, String error, String sysName, String sysDescr,
                        String sysLocation, String sysContact, Long uptimeSeconds) {

        static Probe unreachable(String why) {
            return new Probe(false, why, null, null, null, null, null);
        }
    }

    /** One row of the interface table, already normalised. */
    public record Port(int ifIndex, String name, String alias, String descr,
                       boolean adminUp, boolean operUp, Long speedBps,
                       Long inOctets, Long outOctets, Long inErrors, Long outErrors,
                       boolean sixtyFourBit) {
    }

    /**
     * Asks a device who it is. This doubles as the reachability check, because
     * a device that answers sysName is by definition both up and willing to
     * talk to these credentials — which "responds to ping" never proved.
     */
    public Probe probe(NetworkDevice device) {
        try (Session session = open(device)) {
            Map<String, Variable> values = session.get(
                    SYS_NAME, SYS_DESCR, SYS_LOCATION, SYS_CONTACT, SYS_UPTIME);
            if (values.isEmpty()) {
                return Probe.unreachable("No answer from " + device.getHost() + ":" + device.getPort()
                        + " — check the device is up, the port is right, and SNMP is enabled on it");
            }
            return new Probe(true, null,
                    text(values.get(SYS_NAME)),
                    text(values.get(SYS_DESCR)),
                    text(values.get(SYS_LOCATION)),
                    text(values.get(SYS_CONTACT)),
                    uptimeSeconds(values.get(SYS_UPTIME)));
        } catch (AuthFailure e) {
            return Probe.unreachable(e.getMessage());
        } catch (Exception e) {
            return Probe.unreachable(describe(e));
        }
    }

    /**
     * Every port on the device.
     *
     * <p>The 64-bit counters are read where the device offers them and the
     * 32-bit ones only as a fallback. This is not tidiness: a 32-bit octet
     * counter wraps after 4.3 GB, which on a gigabit uplink is under forty
     * seconds. Polling one every five minutes does not measure traffic, it
     * samples a number that has wrapped an unknown number of times.
     */
    public List<Port> ports(NetworkDevice device) throws Exception {
        try (Session session = open(device)) {
            Map<Integer, Map<String, Variable>> rows = session.walkColumns(
                    IF_DESCR, IF_SPEED, IF_ADMIN_STATUS, IF_OPER_STATUS,
                    IF_IN_OCTETS, IF_IN_ERRORS, IF_OUT_OCTETS, IF_OUT_ERRORS);
            // ifXTable is optional. Absent on old gear, and its absence is not
            // an error — it just means the counters below are the narrow ones.
            Map<Integer, Map<String, Variable>> extended = new LinkedHashMap<>();
            try {
                extended = session.walkColumns(
                        IF_NAME, IF_ALIAS, IF_HC_IN_OCTETS, IF_HC_OUT_OCTETS, IF_HIGH_SPEED);
            } catch (Exception e) {
                log.debug("No ifXTable on {}: {}", device.getName(), e.getMessage());
            }

            List<Port> ports = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, Variable>> entry : rows.entrySet()) {
                int index = entry.getKey();
                Map<String, Variable> base = entry.getValue();
                Map<String, Variable> ext = extended.getOrDefault(index, Map.of());

                Long hcIn = counter(ext.get(IF_HC_IN_OCTETS));
                Long hcOut = counter(ext.get(IF_HC_OUT_OCTETS));
                boolean wide = hcIn != null && hcOut != null;

                // ifHighSpeed is in Mbps and is the only one that can express a
                // 10G link; ifSpeed caps out at 4.29Gbps and reports rubbish above it.
                Long speed = null;
                Long highSpeed = counter(ext.get(IF_HIGH_SPEED));
                if (highSpeed != null && highSpeed > 0) {
                    speed = highSpeed * 1_000_000L;
                } else {
                    Long ifSpeed = counter(base.get(IF_SPEED));
                    if (ifSpeed != null && ifSpeed > 0) {
                        speed = ifSpeed;
                    }
                }

                ports.add(new Port(
                        index,
                        text(ext.get(IF_NAME)),
                        text(ext.get(IF_ALIAS)),
                        text(base.get(IF_DESCR)),
                        // 1 = up, 2 = down, 3 = testing
                        asInt(base.get(IF_ADMIN_STATUS)) == 1,
                        asInt(base.get(IF_OPER_STATUS)) == 1,
                        speed,
                        wide ? hcIn : counter(base.get(IF_IN_OCTETS)),
                        wide ? hcOut : counter(base.get(IF_OUT_OCTETS)),
                        counter(base.get(IF_IN_ERRORS)),
                        counter(base.get(IF_OUT_ERRORS)),
                        wide));
            }
            return ports;
        }
    }

    /** One ONU as the OLT reports it, before any of it is judged. */
    public record Onu(String index, String serial, String description, String status,
                      Double rxDbm, Double txDbm) {
    }

    /**
     * Every ONU an OLT can see, with its optical readings converted to dBm.
     *
     * <p>Read-only. Nothing here can authorise, deauthorise or reconfigure an
     * ONU -- this answers "what is the light doing", which is the question that
     * gets asked, and leaves provisioning to something that can be tested against
     * hardware.
     *
     * <p>Returns empty rather than throwing when the OLT has no such table. A
     * wrong OID is indistinguishable from an OLT with no ONUs at the protocol
     * level, so the caller logs the emptiness and the operator gets the override
     * fields -- see {@link OltProfile}.
     */
    public List<Onu> onus(NetworkDevice device, OltProfile.Columns columns) throws Exception {
        if (columns == null) {
            return List.of();
        }
        try (Session session = open(device)) {
            // Serial and receive power are the two that must be present; the rest
            // are asked for in the same walk and simply absent if the vendor has
            // no such column, which is why they are filtered out rather than
            // assumed.
            List<String> wanted = new ArrayList<>();
            wanted.add(columns.serial());
            wanted.add(columns.rxPower());
            for (String optional : new String[]{columns.txPower(), columns.status(),
                    columns.description()}) {
                if (optional != null && !optional.isBlank()) {
                    wanted.add(optional);
                }
            }
            Map<String, Map<String, Variable>> rows =
                    session.walkColumnsByIndex(wanted.toArray(new String[0]));

            List<Onu> found = new ArrayList<>();
            for (Map.Entry<String, Map<String, Variable>> entry : rows.entrySet()) {
                Map<String, Variable> row = entry.getValue();
                String serial = serialOf(row.get(columns.serial()));
                if (serial == null || serial.isBlank()) {
                    // No serial means nothing durable to store this against, and
                    // storing it against a table index would attribute it to
                    // whoever occupies that row next.
                    continue;
                }
                found.add(new Onu(
                        entry.getKey(),
                        serial,
                        text(row.get(columns.description())),
                        text(row.get(columns.status())),
                        OpticalPower.dbm(counter(row.get(columns.rxPower())),
                                columns.unit(), columns.scale()),
                        columns.txPower() == null ? null
                                : OpticalPower.dbm(counter(row.get(columns.txPower())),
                                        columns.unit(), columns.scale())));
            }
            return found;
        }
    }

    /**
     * An ONU serial, however the vendor chose to send it.
     *
     * <p>Some send printable ASCII; others send eight raw bytes that happen not
     * to be text, and {@link #text} on those returns mojibake that changes
     * between polls -- which would make every ONU look new every time. So a
     * value that is not cleanly printable becomes hex, which is stable and is
     * also how the vendor's own CLI prints it.
     */
    static String serialOf(Variable variable) {
        if (variable == null) {
            return null;
        }
        if (variable instanceof OctetString octets) {
            byte[] bytes = octets.getValue();
            boolean printable = bytes.length > 0;
            for (byte b : bytes) {
                if (b < 0x20 || b > 0x7e) {
                    printable = false;
                    break;
                }
            }
            if (printable) {
                return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            return hex.isEmpty() ? null : hex.toString();
        }
        return text(variable);
    }

    // --- Session plumbing ---

    /** Raised when the device answered but rejected our credentials. */
    static class AuthFailure extends RuntimeException {
        AuthFailure(String message) {
            super(message);
        }
    }

    private static final AtomicInteger ENGINE_SEQ = new AtomicInteger();

    /** Serialises v3 setup, which mutates SNMP4J's process-wide security models. */
    private static final Object V3_SETUP = new Object();

    private Session open(NetworkDevice device) throws IOException {
        return new Session(device);
    }

    /** One SNMP conversation with one device, torn down on close. */
    private static final class Session implements AutoCloseable {

        private final Snmp snmp;
        private final Target<?> target;
        private final NetworkDevice device;

        Session(NetworkDevice device) throws IOException {
            this.device = device;
            this.snmp = new Snmp(new DefaultUdpTransportMapping());
            Address address = GenericAddress.parse("udp:" + device.getHost() + "/" + device.getPort());
            if (address == null) {
                throw new IOException("'" + device.getHost() + "' is not an address we can reach");
            }

            if (device.getSnmpVersion() == NetworkDevice.Version.V3) {
                // SNMP4J keeps its security models in a process-wide singleton,
                // so two threads setting up v3 at once — the scheduled poll and
                // an operator pressing "check now" — would each install a USM
                // over the other's. The symptom would be one of them silently
                // getting no replies, which is indistinguishable from the
                // device being down and would send someone to site for nothing.
                synchronized (V3_SETUP) {
                    // Each session gets its own engine ID. Sharing one across
                    // devices makes SNMP4J cache the wrong remote engine boot
                    // counts and start silently dropping replies.
                    byte[] engineId = MPv3.createLocalEngineID(new OctetString(
                            "hotspot-billing-" + ENGINE_SEQ.incrementAndGet()));
                    USM usm = new USM(SecurityProtocols.getInstance().addDefaultProtocols(),
                            new OctetString(engineId), 0);
                    SecurityModels.getInstance().addSecurityModel(usm);
                    usm.addUser(new OctetString(device.getSecurityName()), new UsmUser(
                            new OctetString(device.getSecurityName()),
                            authOid(device.getAuthProtocol()),
                            passphrase(device.getAuthPassphrase()),
                            privOid(device.getPrivProtocol()),
                            passphrase(device.getPrivPassphrase())));
                }

                UserTarget<Address> t = new UserTarget<>();
                t.setAddress(address);
                t.setVersion(SnmpConstants.version3);
                t.setSecurityName(new OctetString(device.getSecurityName()));
                t.setSecurityLevel(securityLevel(device));
                t.setTimeout(TIMEOUT_MS);
                t.setRetries(RETRIES);
                this.target = t;
            } else {
                CommunityTarget<Address> t = new CommunityTarget<>();
                t.setAddress(address);
                t.setCommunity(new OctetString(
                        device.getCommunity() == null ? "public" : device.getCommunity()));
                t.setVersion(device.getSnmpVersion() == NetworkDevice.Version.V1
                        ? SnmpConstants.version1 : SnmpConstants.version2c);
                t.setTimeout(TIMEOUT_MS);
                t.setRetries(RETRIES);
                this.target = t;
            }
            snmp.listen();
        }

        /** Fetches scalar OIDs in one request. Empty when nothing answered. */
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, Variable> get(String... oids) throws IOException {
            PDU pdu = device.getSnmpVersion() == NetworkDevice.Version.V3 ? new ScopedPDU() : new PDU();
            pdu.setType(PDU.GET);
            for (String oid : oids) {
                pdu.add(new VariableBinding(new OID(oid)));
            }
            var event = snmp.send(pdu, (Target) target);
            PDU response = event == null ? null : event.getResponse();
            if (response == null) {
                return Map.of();
            }
            if (response.getErrorStatus() == PDU.authorizationError) {
                throw new AuthFailure("The device rejected those SNMP credentials");
            }
            Map<String, Variable> out = new LinkedHashMap<>();
            for (VariableBinding binding : response.getVariableBindings()) {
                if (binding.isException()) {
                    continue; // this OID isn't implemented here; others may be
                }
                out.put(binding.getOid().toDottedString(), binding.getVariable());
            }
            return out;
        }

        /**
         * Walks several columns of one table together, returning them keyed by
         * row index. Done as one walk rather than one per column because a
         * 48-port switch would otherwise take eight separate passes, during
         * which the counters move and stop lining up with each other.
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<Integer, Map<String, Variable>> walkColumns(String... columns) throws IOException {
            TableUtils tableUtils = new TableUtils(snmp,
                    new DefaultPDUFactory(device.getSnmpVersion() == NetworkDevice.Version.V3
                            ? PDU.GETBULK : PDU.GETNEXT));
            tableUtils.setMaxNumColumnsPerPDU(columns.length);
            OID[] oids = new OID[columns.length];
            for (int i = 0; i < columns.length; i++) {
                oids[i] = new OID(columns[i]);
            }

            Map<Integer, Map<String, Variable>> rows = new LinkedHashMap<>();
            for (TableEvent event : tableUtils.getTable((Target) target, oids, null, null)) {
                if (event.isError() || event.getIndex() == null) {
                    continue;
                }
                int index;
                try {
                    index = event.getIndex().last();
                } catch (Exception e) {
                    continue;
                }
                Map<String, Variable> row = rows.computeIfAbsent(index, k -> new LinkedHashMap<>());
                VariableBinding[] bindings = event.getColumns();
                for (int i = 0; i < columns.length && i < bindings.length; i++) {
                    if (bindings[i] != null && !bindings[i].isException()) {
                        row.put(columns[i], bindings[i].getVariable());
                    }
                }
            }
            return rows;
        }

        /**
         * The same, keyed on the whole index rather than its last element.
         *
         * <p>{@link #walkColumns} takes {@code index.last()}, which is right for
         * ifTable where the index is a single number. GPON ONU tables are indexed
         * on the PON port <em>and</em> the ONU within it, so the last element is
         * only the ONU number -- and ONU 1 on port 1 and ONU 1 on port 2 would
         * collapse into one row, with one customer's optical reading overwriting
         * another's. The full index keeps them apart.
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, Map<String, Variable>> walkColumnsByIndex(String... columns) throws IOException {
            TableUtils tableUtils = new TableUtils(snmp,
                    new DefaultPDUFactory(device.getSnmpVersion() == NetworkDevice.Version.V3
                            ? PDU.GETBULK : PDU.GETNEXT));
            tableUtils.setMaxNumColumnsPerPDU(columns.length);
            OID[] oids = new OID[columns.length];
            for (int i = 0; i < columns.length; i++) {
                oids[i] = new OID(columns[i]);
            }
            Map<String, Map<String, Variable>> rows = new LinkedHashMap<>();
            for (TableEvent event : tableUtils.getTable((Target) target, oids, null, null)) {
                if (event.isError() || event.getIndex() == null) {
                    continue;
                }
                String index = event.getIndex().toString();
                Map<String, Variable> row = rows.computeIfAbsent(index, k -> new LinkedHashMap<>());
                VariableBinding[] bindings = event.getColumns();
                for (int i = 0; i < columns.length && i < bindings.length; i++) {
                    if (bindings[i] != null && !bindings[i].isException()) {
                        row.put(columns[i], bindings[i].getVariable());
                    }
                }
            }
            return rows;
        }

        @Override
        public void close() {
            try {
                snmp.close();
            } catch (IOException e) {
                log.debug("SNMP session close: {}", e.getMessage());
            }
        }
    }

    // --- Conversions ---

    private static OID authOid(NetworkDevice.AuthProtocol protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol) {
            case NONE -> null;
            case MD5 -> AuthMD5.ID;
            case SHA1 -> AuthSHA.ID;
            case SHA224 -> AuthHMAC128SHA224.ID;
            case SHA256 -> AuthHMAC192SHA256.ID;
            case SHA384 -> AuthHMAC256SHA384.ID;
            case SHA512 -> AuthHMAC384SHA512.ID;
        };
    }

    private static OID privOid(NetworkDevice.PrivProtocol protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol) {
            case NONE -> null;
            case DES -> PrivDES.ID;
            case TRIPLE_DES -> Priv3DES.ID;
            case AES128 -> PrivAES128.ID;
            case AES192 -> PrivAES192.ID;
            case AES256 -> PrivAES256.ID;
        };
    }

    private static OctetString passphrase(String value) {
        return value == null || value.isBlank() ? null : new OctetString(value);
    }

    private static int securityLevel(NetworkDevice device) {
        boolean auth = device.getAuthProtocol() != null
                && device.getAuthProtocol() != NetworkDevice.AuthProtocol.NONE
                && device.getAuthPassphrase() != null && !device.getAuthPassphrase().isBlank();
        boolean priv = auth && device.getPrivProtocol() != null
                && device.getPrivProtocol() != NetworkDevice.PrivProtocol.NONE
                && device.getPrivPassphrase() != null && !device.getPrivPassphrase().isBlank();
        if (priv) {
            return SecurityLevel.AUTH_PRIV;
        }
        return auth ? SecurityLevel.AUTH_NOPRIV : SecurityLevel.NOAUTH_NOPRIV;
    }

    static String text(Variable variable) {
        if (variable == null) {
            return null;
        }
        String value = variable instanceof OctetString octet ? octet.toString() : variable.toString();
        if (value == null || value.isBlank()) {
            return null;
        }
        // Devices pad strings with NULs and embed newlines in sysDescr.
        // Neither belongs in a single-line column; the spaces inside
        // "uplink to core" very much do.
        value = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s{2,}", " ").trim();
        return value.isEmpty() ? null : (value.length() > 250 ? value.substring(0, 250) : value);
    }

    static Long counter(Variable variable) {
        if (variable == null || variable instanceof Null) {
            return null;
        }
        try {
            long value = variable.toLong();
            return value < 0 ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private static int asInt(Variable variable) {
        Long value = counter(variable);
        return value == null ? -1 : value.intValue();
    }

    /** sysUpTime is in hundredths of a second, which nobody wants to read. */
    static Long uptimeSeconds(Variable variable) {
        Long ticks = counter(variable);
        return ticks == null ? null : ticks / 100;
    }

    /** Turns a stack trace into something an operator can act on. */
    static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (message.contains("UnknownHost") || e instanceof java.net.UnknownHostException) {
            return "That hostname doesn't resolve";
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
