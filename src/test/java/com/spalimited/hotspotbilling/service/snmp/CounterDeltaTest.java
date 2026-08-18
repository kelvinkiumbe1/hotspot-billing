package com.spalimited.hotspotbilling.service.snmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning two cumulative counter readings into a traffic rate.
 *
 * <p>Worth testing hard because every failure here is silent. A wrap mistaken
 * for a reset loses four gigabytes; a reset mistaken for a wrap invents four
 * gigabytes. Neither throws, and both end up in the capacity figures an
 * operator uses to decide whether to buy more bandwidth.
 */
class CounterDeltaTest {

    private static final long GIGABIT = 1_000_000_000L;
    private static final long WRAP = 1L << 32;

    private static Long delta(long previous, long current, boolean wide, Long speed, long seconds) {
        return DeviceMonitorService.octetDelta(previous, current, wide, speed, seconds);
    }

    @Test
    @DisplayName("The ordinary case is just the difference")
    void climbing() {
        assertThat(delta(1_000, 5_000, true, GIGABIT, 300)).isEqualTo(4_000);
    }

    @Test
    @DisplayName("A 32-bit counter that wrapped is corrected, not discarded")
    void wrapCorrected() {
        // 100 MB before the wrap point, 50 MB after it: 150 MB in a minute on a
        // 100M link, which is within what that link could have carried.
        long previous = WRAP - 100_000_000L;
        long current = 50_000_000L;
        assertThat(delta(previous, current, false, 100_000_000L, 60)).isEqualTo(150_000_000L);
    }

    @Test
    @DisplayName("A wrap that would need more bandwidth than the link has is not a wrap")
    void implausibleWrapRejected() {
        // Counter fell from 1,000 to 100. As a wrap that is the whole 4.29 GB
        // range in ten seconds — 3.4 Gbps on a 100M port, which did not happen.
        // It is a device that rebooted and cleared its counters.
        assertThat(delta(1_000, 100, false, 100_000_000L, 10)).isNull();
    }

    @Test
    @DisplayName("When the link could have wrapped the counter twice, the reading is unusable")
    void multipleWrapsAreUnknowable() {
        // A gigabit port carries 37 GB in five minutes and the counter only
        // holds 4.29 GB, so it has gone round an unknown number of times.
        // Correcting for one wrap would be arithmetic on a meaningless number.
        assertThat(delta(WRAP - 100_000_000L, 50_000_000L, false, GIGABIT, 300)).isNull();
        // The same two readings from the 64-bit counter are fine, which is the
        // whole reason those are read in preference.
        assertThat(delta(WRAP - 100_000_000L, WRAP + 50_000_000L, true, GIGABIT, 300))
                .isEqualTo(150_000_000L);
    }

    @Test
    @DisplayName("A 64-bit counter going backwards is always a reset")
    void sixtyFourBitNeverWraps() {
        // 18 exabytes has not passed through a switch port. The counter was cleared.
        assertThat(delta(5_000_000_000L, 1_000, true, GIGABIT, 300)).isNull();
    }

    @Test
    @DisplayName("Without a link speed there is no way to tell a wrap from a reset, so neither is claimed")
    void noSpeedNoGuess() {
        assertThat(delta(WRAP - 100, 100, false, null, 300)).isNull();
        assertThat(delta(WRAP - 100, 100, false, 0L, 300)).isNull();
    }

    @Test
    @DisplayName("A wrap right at the boundary is still counted")
    void exactBoundary() {
        assertThat(delta(WRAP - 1, 0, false, 100_000_000L, 60)).isEqualTo(1);
    }

    @Test
    @DisplayName("Identical readings mean an idle link, not a missing one")
    void idle() {
        assertThat(delta(42, 42, true, GIGABIT, 300)).isZero();
    }

    @Test
    @DisplayName("Uptime reads as something a person would say out loud")
    void uptimeWording() {
        assertThat(DeviceMonitorService.humanUptime(90)).isEqualTo("1m");
        assertThat(DeviceMonitorService.humanUptime(3_700)).isEqualTo("1h 1m");
        assertThat(DeviceMonitorService.humanUptime(200_000)).isEqualTo("2d 7h");
    }
}
