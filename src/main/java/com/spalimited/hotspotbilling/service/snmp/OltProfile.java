package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where on an OLT to find its ONUs, per vendor.
 *
 * <p>There is no standard for this. GPON ONU tables live in enterprise MIBs and
 * every vendor invented their own, so the OIDs below are presets rather than
 * facts — and unlike every payment API in this codebase there is no sandbox
 * anywhere to check them against. A wrong OID here returns nothing, which reads
 * as "this OLT has no ONUs" rather than as an error.
 *
 * <p>Which is why every one of them is overridable per device. An operator
 * standing in front of their own OLT with {@code snmpwalk} can find the right
 * column and type it into the admin, and their ONUs appear — without waiting for
 * a release, and without anybody having to guess again. The preset is a starting
 * point; the override is the answer.
 *
 * <p>The scale matters as much as the OID and is overridable for the same
 * reason: see {@link OpticalPower}, where reading a good fibre with the wrong
 * scale is shown to be worse than not reading it at all.
 */
public final class OltProfile {

    /**
     * A vendor's columns.
     *
     * <p>{@code serial} is the ONU's serial number or MAC, which is the only
     * durable way to recognise the same ONU between polls — the table index moves
     * when ONUs are added and removed, so keying on it would silently attribute
     * one customer's readings to another.
     */
    public record Columns(String serial, String rxPower, String txPower, String status,
                          String description, OpticalPower.Unit unit, double scale) {
    }

    /**
     * The presets, best-effort and clearly labelled as such.
     *
     * <p>Huawei's are the ones most likely to be right: the MA5600 and MA5800
     * families are the commonest OLTs in African fibre networks and their
     * hwGponDeviceOnt tables are the most widely documented. The rest are
     * starting points.
     */
    private static final Map<NetworkDevice.OltVendor, Columns> PRESETS = new LinkedHashMap<>();

    static {
        // Huawei MA5600 / MA5800. hwGponDeviceOntSn, and the DDM table for optics.
        PRESETS.put(NetworkDevice.OltVendor.HUAWEI, new Columns(
                "1.3.6.1.4.1.2011.6.128.1.1.2.43.1.3",   // hwGponDeviceOntSn
                "1.3.6.1.4.1.2011.6.128.1.1.2.51.1.4",   // hwGponOntOpticalDdmRxPower
                "1.3.6.1.4.1.2011.6.128.1.1.2.51.1.6",   // hwGponOntOpticalDdmTxPower
                "1.3.6.1.4.1.2011.6.128.1.1.2.46.1.15",  // hwGponDeviceOntControlRunStatus
                "1.3.6.1.4.1.2011.6.128.1.1.2.43.1.9",   // hwGponDeviceOntDespt
                OpticalPower.Unit.DBM_SCALED, 100.0));

        // ZTE C300 / C320. zxAnOnu tables.
        PRESETS.put(NetworkDevice.OltVendor.ZTE, new Columns(
                "1.3.6.1.4.1.3902.1012.3.28.1.1.5",
                "1.3.6.1.4.1.3902.1012.3.50.12.1.1.10",
                "1.3.6.1.4.1.3902.1012.3.50.12.1.1.14",
                "1.3.6.1.4.1.3902.1012.3.28.2.1.2",
                "1.3.6.1.4.1.3902.1012.3.28.1.1.2",
                OpticalPower.Unit.DBM_SCALED, 100.0));

        // VSOL and the many BDCOM-derived OLTs sold as house brands.
        PRESETS.put(NetworkDevice.OltVendor.VSOL, new Columns(
                "1.3.6.1.4.1.37950.1.1.5.10.1.1.3",
                "1.3.6.1.4.1.37950.1.1.5.12.1.1.10",
                "1.3.6.1.4.1.37950.1.1.5.12.1.1.9",
                "1.3.6.1.4.1.37950.1.1.5.10.1.1.9",
                "1.3.6.1.4.1.37950.1.1.5.10.1.1.4",
                OpticalPower.Unit.DBM_SCALED, 100.0));

        PRESETS.put(NetworkDevice.OltVendor.BDCOM, new Columns(
                "1.3.6.1.4.1.3320.101.10.1.1.3",
                "1.3.6.1.4.1.3320.101.10.5.1.5",
                "1.3.6.1.4.1.3320.101.10.5.1.6",
                "1.3.6.1.4.1.3320.101.10.1.1.26",
                "1.3.6.1.4.1.3320.101.10.1.1.2",
                OpticalPower.Unit.DBM_SCALED, 10.0));

        PRESETS.put(NetworkDevice.OltVendor.FIBERHOME, new Columns(
                "1.3.6.1.4.1.5875.800.3.9.3.3.1.3",
                "1.3.6.1.4.1.5875.800.3.9.4.2.1.2",
                "1.3.6.1.4.1.5875.800.3.9.4.2.1.3",
                "1.3.6.1.4.1.5875.800.3.9.3.3.1.4",
                "1.3.6.1.4.1.5875.800.3.9.3.3.1.2",
                OpticalPower.Unit.DBM_SCALED, 100.0));
    }

    private OltProfile() {
    }

    /**
     * The columns to walk for one device: its vendor's preset, with anything the
     * operator has typed in taking precedence.
     *
     * <p>Returns null when there is nothing usable — no vendor chosen and no
     * overrides — because walking a made-up OID wastes a poll and reports an
     * empty OLT, which looks like a working integration finding nothing.
     */
    public static Columns forDevice(NetworkDevice device) {
        if (device == null) {
            return null;
        }
        Columns preset = device.getOltVendor() == null ? null : PRESETS.get(device.getOltVendor());

        String serial = pick(device.getOnuSerialOid(), preset == null ? null : preset.serial());
        String rx = pick(device.getOnuRxPowerOid(), preset == null ? null : preset.rxPower());
        String tx = pick(device.getOnuTxPowerOid(), preset == null ? null : preset.txPower());
        String status = pick(device.getOnuStatusOid(), preset == null ? null : preset.status());
        String descr = preset == null ? null : preset.description();

        // Serial and receive power are the two that make this worth polling: one
        // says which ONU, the other is the reading anybody actually wants. Without
        // both there is nothing to store against anything.
        if (serial == null || rx == null) {
            return null;
        }
        OpticalPower.Unit unit = device.getOnuPowerUnit() != null
                ? device.getOnuPowerUnit()
                : preset != null ? preset.unit() : OpticalPower.Unit.DBM_SCALED;
        double scale = device.getOnuPowerScale() != null && device.getOnuPowerScale() != 0
                ? device.getOnuPowerScale()
                : preset != null ? preset.scale() : 100.0;
        return new Columns(serial, rx, tx, status, descr, unit, scale);
    }

    /** For the admin, so the form can show what a vendor's preset actually is. */
    public static Columns preset(NetworkDevice.OltVendor vendor) {
        return vendor == null ? null : PRESETS.get(vendor);
    }

    private static String pick(String override, String preset) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return preset == null || preset.isBlank() ? null : preset;
    }
}
