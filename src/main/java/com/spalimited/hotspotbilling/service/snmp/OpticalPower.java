package com.spalimited.hotspotbilling.service.snmp;

/**
 * Turning what an OLT reports into a number a technician can act on.
 *
 * <p>Every GPON vendor reports optical power differently and none of them
 * reports dBm. Huawei sends hundredths of a dBm as a signed integer, so
 * {@code -2456} means −24.56 dBm. Others send thousandths, or tenths, or the raw
 * power in microwatts needing a logarithm. Read with the wrong scale, a perfectly
 * healthy −24 dBm drop reads as −2456 or as 0.02, and either way the reading is
 * worse than not having one: a technician sent to a good fibre is a technician
 * not sent to the broken one.
 *
 * <p>So the scale is a property of the device, not of this code, and this class
 * is where it gets applied and where the result is judged. The judgement is the
 * point. "−24.6 dBm" means nothing to most people; "fine", "getting marginal" and
 * "this drop is failing" are what gets a van dispatched.
 */
public final class OpticalPower {

    /**
     * How a vendor encodes the number it sends.
     *
     * <p>{@code DBM_SCALED} covers everyone who sends a fixed-point dBm — the
     * scale says how many places. {@code MICROWATT} covers the ones who send
     * optical power directly and expect the reader to do the logarithm.
     */
    public enum Unit { DBM_SCALED, MICROWATT }

    /**
     * Values that mean "no reading", not "a reading of zero".
     *
     * <p>Each of these is a real sentinel seen in the field: 2147483647 is a
     * signed 32-bit maximum used for "unsupported", 65535 the unsigned 16-bit
     * one, and −1 and 0 are both used for "the ONU is not there". Treating any of
     * them as a measurement puts a fabricated number on a technician's screen,
     * and 0 is the dangerous one because 0 dBm is a plausible-looking value that
     * would read as a catastrophically hot receiver.
     */
    private static final long[] NOT_AVAILABLE = {2147483647L, -2147483648L, 65535L, -1L, 0L};

    /** GPON class B+ receive budget, and what a technician should do about it. */
    public enum Health {
        /** Above the receiver's maximum. Too close, or a missing attenuator. */
        TOO_HOT,
        /** Comfortably inside the budget. */
        GOOD,
        /** Inside the budget but with little left. Worth a look before it fails. */
        MARGINAL,
        /** Past the budget. Expect drops, retransmits and complaints. */
        BAD,
        /** No light. The fibre is broken, unplugged, or the ONT is off. */
        DOWN,
        /** The OLT did not give a number, which is not the same as a bad one. */
        UNKNOWN,
    }

    /**
     * Where the boundaries are, in dBm.
     *
     * <p>These are the GPON class B+ figures, not a preference: a B+ receiver is
     * specified from −8 down to −27 dBm. Below −28 the link is outside what the
     * optics are rated for, and past −30 it does not stay up at all.
     */
    public static final double TOO_HOT_ABOVE = -8.0;
    public static final double MARGINAL_BELOW = -25.0;
    public static final double BAD_BELOW = -27.0;
    public static final double DOWN_BELOW = -30.0;

    /**
     * A drop worth telling somebody about.
     *
     * <p>Three decibels is half the light. Anything less is weather, temperature
     * and the ordinary drift of a working link; 3 dB in one polling interval is
     * something that physically changed — a bend, a dirty connector, a splice
     * somebody leaned on.
     */
    public static final double ALERT_DROP_DB = 3.0;

    private OpticalPower() {
    }

    /**
     * The reading in dBm, or null when the OLT did not give one.
     *
     * <p>Null rather than zero, and rather than an Optional, because it lands in
     * a nullable database column and "no reading" has to survive that round trip
     * as itself. A zero here would be read as a receiver being blasted.
     */
    public static Double dbm(Long raw, Unit unit, Double scale) {
        if (raw == null) {
            return null;
        }
        for (long sentinel : NOT_AVAILABLE) {
            if (raw == sentinel) {
                return null;
            }
        }
        if (unit == Unit.MICROWATT) {
            // 0 is already excluded above, and log10 of a negative is not a
            // number -- a negative microwatt reading is a broken agent, not a
            // very dark fibre.
            if (raw < 0) {
                return null;
            }
            return round(10.0 * Math.log10(raw / 1000.0));
        }
        double divisor = scale == null || scale == 0 ? 100.0 : scale;
        return round(raw / divisor);
    }

    /** What a technician should conclude from it. */
    public static Health health(Double dbm) {
        if (dbm == null) {
            return Health.UNKNOWN;
        }
        if (dbm < DOWN_BELOW) {
            return Health.DOWN;
        }
        if (dbm < BAD_BELOW) {
            return Health.BAD;
        }
        if (dbm < MARGINAL_BELOW) {
            return Health.MARGINAL;
        }
        if (dbm > TOO_HOT_ABOVE) {
            return Health.TOO_HOT;
        }
        return Health.GOOD;
    }

    /**
     * Whether a change between two readings is worth an alert.
     *
     * <p>Only downwards. A link that improved by 4 dB is somebody having cleaned
     * a connector, which is good news and not an alert — and treating it as one
     * teaches an operator to ignore these.
     *
     * <p>A first reading is never an alert either. Without a previous value there
     * is no change, and firing on every newly discovered ONT would mean the first
     * poll after adding an OLT sends a hundred messages.
     */
    public static boolean worthAlerting(Double previous, Double current) {
        if (previous == null || current == null) {
            return false;
        }
        return previous - current >= ALERT_DROP_DB;
    }

    /** One decimal place, which is finer than the optics are accurate to. */
    private static Double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.round(value * 10.0) / 10.0;
    }
}
