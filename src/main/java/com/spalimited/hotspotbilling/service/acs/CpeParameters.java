package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.domain.CpeDevice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The same setting, under the two names the industry gave it.
 *
 * <p>TR-069 standardised the protocol and then two different data models were
 * standardised on top of it. TR-098 puts a router's WiFi password at
 * {@code InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase};
 * TR-181 puts it at {@code Device.WiFi.AccessPoint.1.Security.KeyPassphrase}.
 * Both are current, both are in the field, and a box bought this year could be
 * either.
 *
 * <p>Which makes this class the difference between a feature and a demo. An
 * operator wants to change a customer's WiFi password; they do not want to know
 * which data model that customer's router happens to speak, and asking them is
 * asking a question they cannot answer. So the admin deals in
 * {@link Setting#WIFI_PASSWORD} and this turns it into whichever path the device
 * will accept.
 *
 * <p>Getting it wrong is not a clean failure. A CPE sent a path it does not know
 * usually returns fault 9005 "Invalid parameter name", which is at least
 * visible — but some accept the write, report success and change nothing, and
 * the operator tells the customer their password is changed when it is not.
 */
public final class CpeParameters {

    /** The things an operator actually wants to change, named their way. */
    public enum Setting {
        WIFI_SSID,
        WIFI_PASSWORD,
        WIFI_ENABLED,
        /** The box's own admin password, which an ISP should not leave at default. */
        ADMIN_PASSWORD,
        /** Software version, read-only, and the thing a firmware rollout checks. */
        SOFTWARE_VERSION,
        /** How often the CPE calls in. Shorter means changes land faster. */
        INFORM_INTERVAL,
        /** Where the CPE can be poked, which it reports rather than being told. */
        CONNECTION_REQUEST_URL,
        /** Uptime, for answering "has this box been rebooting?" */
        UPTIME,
    }

    private static final Map<Setting, String> TR098 = new LinkedHashMap<>();
    private static final Map<Setting, String> TR181 = new LinkedHashMap<>();

    static {
        TR098.put(Setting.WIFI_SSID,
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.SSID");
        TR098.put(Setting.WIFI_PASSWORD,
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.KeyPassphrase");
        TR098.put(Setting.WIFI_ENABLED,
                "InternetGatewayDevice.LANDevice.1.WLANConfiguration.1.Enable");
        TR098.put(Setting.ADMIN_PASSWORD,
                "InternetGatewayDevice.User.1.Password");
        TR098.put(Setting.SOFTWARE_VERSION,
                "InternetGatewayDevice.DeviceInfo.SoftwareVersion");
        TR098.put(Setting.INFORM_INTERVAL,
                "InternetGatewayDevice.ManagementServer.PeriodicInformInterval");
        TR098.put(Setting.CONNECTION_REQUEST_URL,
                "InternetGatewayDevice.ManagementServer.ConnectionRequestURL");
        TR098.put(Setting.UPTIME,
                "InternetGatewayDevice.DeviceInfo.UpTime");

        TR181.put(Setting.WIFI_SSID, "Device.WiFi.SSID.1.SSID");
        TR181.put(Setting.WIFI_PASSWORD, "Device.WiFi.AccessPoint.1.Security.KeyPassphrase");
        TR181.put(Setting.WIFI_ENABLED, "Device.WiFi.SSID.1.Enable");
        TR181.put(Setting.ADMIN_PASSWORD, "Device.Users.User.1.Password");
        TR181.put(Setting.SOFTWARE_VERSION, "Device.DeviceInfo.SoftwareVersion");
        TR181.put(Setting.INFORM_INTERVAL, "Device.ManagementServer.PeriodicInformInterval");
        TR181.put(Setting.CONNECTION_REQUEST_URL,
                "Device.ManagementServer.ConnectionRequestURL");
        TR181.put(Setting.UPTIME, "Device.DeviceInfo.UpTime");
    }

    private CpeParameters() {
    }

    /**
     * The path for one setting on one device, or null if we cannot tell.
     *
     * <p>Null when the data model is unknown, and that is deliberate. Guessing
     * TR-098 because it is commoner would be right most of the time and silently
     * wrong the rest — and "silently wrong" here means telling a customer their
     * password changed when it did not. The caller asks the device what it is
     * first; see {@link #ROOT_PROBE}.
     */
    public static String path(CpeDevice.DataModel model, Setting setting) {
        if (model == null || setting == null) {
            return null;
        }
        return switch (model) {
            case TR098 -> TR098.get(setting);
            case TR181 -> TR181.get(setting);
            case UNKNOWN -> null;
        };
    }

    /**
     * What to ask a device whose data model we do not know yet.
     *
     * <p>Both roots at once. A device answers with a value for the one it
     * implements and a fault or an empty result for the other, which settles it
     * in a single round trip — and asking for a whole model root is one request
     * rather than a hunt.
     */
    public static final List<String> ROOT_PROBE = List.of(
            "InternetGatewayDevice.DeviceInfo.SoftwareVersion",
            "Device.DeviceInfo.SoftwareVersion");

    /**
     * Which data model a set of reported parameter names implies.
     *
     * <p>Read off the names a device volunteered rather than asked for
     * separately: every Inform carries a parameter list, and its paths already
     * say which model the device speaks. One fewer round trip on the very first
     * contact, which is the contact most likely to be over a bad link.
     */
    public static CpeDevice.DataModel detect(Iterable<String> reportedNames) {
        if (reportedNames == null) {
            return CpeDevice.DataModel.UNKNOWN;
        }
        boolean igd = false;
        boolean device = false;
        for (String name : reportedNames) {
            if (name == null) {
                continue;
            }
            if (name.startsWith("InternetGatewayDevice.")) {
                igd = true;
            } else if (name.startsWith("Device.")) {
                device = true;
            }
        }
        // A handful of devices report both roots -- TR-181 has an optional
        // InternetGatewayDevice compatibility branch. TR-181 wins there, because
        // a device that implements both is a TR-181 device being polite and its
        // native paths are the ones that will not be deprecated out from under us.
        if (device) {
            return CpeDevice.DataModel.TR181;
        }
        return igd ? CpeDevice.DataModel.TR098 : CpeDevice.DataModel.UNKNOWN;
    }

    /** Everything worth reading on a first contact, for the model we now know. */
    public static List<String> interestingPaths(CpeDevice.DataModel model) {
        if (model == null || model == CpeDevice.DataModel.UNKNOWN) {
            return ROOT_PROBE;
        }
        return List.of(
                path(model, Setting.WIFI_SSID),
                path(model, Setting.WIFI_ENABLED),
                path(model, Setting.SOFTWARE_VERSION),
                path(model, Setting.UPTIME),
                path(model, Setting.CONNECTION_REQUEST_URL));
    }
}
