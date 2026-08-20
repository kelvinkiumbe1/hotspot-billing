package com.spalimited.hotspotbilling.service.snmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a fibre correctly, which nothing else in this feature can do for us.
 *
 * <p>The OIDs an OLT answers on are vendor-specific and cannot be verified
 * without the hardware. The arithmetic can, and it is where the damage lives: a
 * healthy drop read with the wrong scale becomes −2456 or −0.2, and a technician
 * is sent to a working fibre while the broken one waits.
 */
class OpticalPowerTest {

    // ------------------------------------------------------------- the scaling

    @Test
    @DisplayName("Hundredths of a dBm, which is what Huawei sends")
    void hundredths() {
        // -2456 is -24.56 dBm: an ordinary, healthy drop.
        assertThat(OpticalPower.dbm(-2456L, OpticalPower.Unit.DBM_SCALED, 100.0)).isEqualTo(-24.6);
        assertThat(OpticalPower.dbm(-800L, OpticalPower.Unit.DBM_SCALED, 100.0)).isEqualTo(-8.0);
    }

    @Test
    @DisplayName("Thousandths, and tenths, which other vendors send")
    void otherScales() {
        assertThat(OpticalPower.dbm(-24560L, OpticalPower.Unit.DBM_SCALED, 1000.0)).isEqualTo(-24.6);
        assertThat(OpticalPower.dbm(-246L, OpticalPower.Unit.DBM_SCALED, 10.0)).isEqualTo(-24.6);
    }

    @Test
    @DisplayName("Microwatts, where the reader has to do the logarithm")
    void microwatts() {
        // 1000 uW is 1 mW is 0 dBm, by definition -- the reference point.
        assertThat(OpticalPower.dbm(1000L, OpticalPower.Unit.MICROWATT, null)).isEqualTo(0.0);
        // 100 uW is a tenth of a milliwatt: -10 dBm.
        assertThat(OpticalPower.dbm(100L, OpticalPower.Unit.MICROWATT, null)).isEqualTo(-10.0);
        // And a realistic drop: about 3.5 uW is around -24.6 dBm.
        assertThat(OpticalPower.dbm(3L, OpticalPower.Unit.MICROWATT, null)).isEqualTo(-25.2);
    }

    @Test
    @DisplayName("A missing scale falls back to hundredths rather than to raw")
    void missingScaleDoesNotReturnRaw() {
        // Returning the raw number would put -2456 dBm on a screen. Hundredths is
        // the commonest encoding, so it is the least wrong guess -- and the
        // operator can correct the scale per device.
        assertThat(OpticalPower.dbm(-2456L, OpticalPower.Unit.DBM_SCALED, null)).isEqualTo(-24.6);
        assertThat(OpticalPower.dbm(-2456L, OpticalPower.Unit.DBM_SCALED, 0.0)).isEqualTo(-24.6);
    }

    // ------------------------------------------------------- what is not a reading

    @Test
    @DisplayName("Every sentinel an OLT uses for \"no reading\" stays no reading")
    void sentinelsAreNotMeasurements() {
        // Each of these is real, and each would otherwise become a number on a
        // technician's screen.
        for (long sentinel : new long[]{2147483647L, -2147483648L, 65535L, -1L, 0L}) {
            assertThat(OpticalPower.dbm(sentinel, OpticalPower.Unit.DBM_SCALED, 100.0))
                    .as("raw %d", sentinel).isNull();
        }
        assertThat(OpticalPower.dbm(null, OpticalPower.Unit.DBM_SCALED, 100.0)).isNull();
    }

    @Test
    @DisplayName("Zero is the dangerous one, and it is excluded")
    void zeroIsNotZeroDbm() {
        // 0 dBm is a plausible-looking value that would read as a receiver being
        // blasted -- so an absent reading encoded as 0 must not become one.
        assertThat(OpticalPower.dbm(0L, OpticalPower.Unit.DBM_SCALED, 100.0)).isNull();
        assertThat(OpticalPower.dbm(0L, OpticalPower.Unit.MICROWATT, null)).isNull();
    }

    @Test
    @DisplayName("A negative microwatt reading is a broken agent, not a dark fibre")
    void negativeMicrowattsAreRejected() {
        assertThat(OpticalPower.dbm(-50L, OpticalPower.Unit.MICROWATT, null)).isNull();
    }

    // ------------------------------------------------------------ the judgement

    @Test
    @DisplayName("The GPON class B+ budget, at its boundaries")
    void healthBands() {
        assertThat(OpticalPower.health(null)).isEqualTo(OpticalPower.Health.UNKNOWN);
        // Above the receiver's maximum: too close, or a missing attenuator.
        assertThat(OpticalPower.health(-5.0)).isEqualTo(OpticalPower.Health.TOO_HOT);
        assertThat(OpticalPower.health(-8.0)).isEqualTo(OpticalPower.Health.GOOD);
        assertThat(OpticalPower.health(-20.0)).isEqualTo(OpticalPower.Health.GOOD);
        // Inside the budget with little left.
        assertThat(OpticalPower.health(-25.1)).isEqualTo(OpticalPower.Health.MARGINAL);
        assertThat(OpticalPower.health(-26.9)).isEqualTo(OpticalPower.Health.MARGINAL);
        // Past the budget.
        assertThat(OpticalPower.health(-27.5)).isEqualTo(OpticalPower.Health.BAD);
        // No light at all.
        assertThat(OpticalPower.health(-35.0)).isEqualTo(OpticalPower.Health.DOWN);
    }

    @Test
    @DisplayName("A reading exactly on a boundary is not the worse side of it")
    void boundariesAreNotOffByOne() {
        // -25.0 is still within spec; -27.0 is still the marginal band rather
        // than bad. An off-by-one here dispatches a van for a working link.
        assertThat(OpticalPower.health(-25.0)).isEqualTo(OpticalPower.Health.GOOD);
        assertThat(OpticalPower.health(-27.0)).isEqualTo(OpticalPower.Health.MARGINAL);
        assertThat(OpticalPower.health(-30.0)).isEqualTo(OpticalPower.Health.BAD);
    }

    // ----------------------------------------------------------- what to alert on

    @Test
    @DisplayName("Half the light gone is an alert")
    void aThreeDecibelDropAlerts() {
        // 3 dB is half the optical power. Less than that is weather and
        // temperature; 3 dB between two polls is something that physically moved.
        assertThat(OpticalPower.worthAlerting(-20.0, -23.0)).isTrue();
        assertThat(OpticalPower.worthAlerting(-20.0, -26.0)).isTrue();
        assertThat(OpticalPower.worthAlerting(-20.0, -22.9)).isFalse();
    }

    @Test
    @DisplayName("An improvement is not an alert")
    void gettingBetterIsNotAnAlert() {
        // Somebody cleaned a connector. Alerting on good news is how an operator
        // learns to ignore these.
        assertThat(OpticalPower.worthAlerting(-26.0, -20.0)).isFalse();
    }

    @Test
    @DisplayName("A first reading is never an alert")
    void theFirstReadingIsNotAChange() {
        // Otherwise the first poll after adding an OLT sends one message per ONT,
        // which for a full OLT is hundreds.
        assertThat(OpticalPower.worthAlerting(null, -28.0)).isFalse();
        assertThat(OpticalPower.worthAlerting(-20.0, null)).isFalse();
    }

    @Test
    @DisplayName("An ONT going dark is caught by the drop as well as the band")
    void goingDarkIsBothThings() {
        // The band says it is down; the change says when. Both matter: a link
        // that has always been -28 is a bad install, and one that was -20
        // yesterday is a fibre somebody cut this morning.
        assertThat(OpticalPower.health(-31.0)).isEqualTo(OpticalPower.Health.DOWN);
        assertThat(OpticalPower.worthAlerting(-20.0, -31.0)).isTrue();
    }
}
