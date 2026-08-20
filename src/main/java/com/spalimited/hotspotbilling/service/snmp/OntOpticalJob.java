package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.service.HeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads the light on every OLT, on a slower timer than the switches.
 *
 * <p>Fifteen minutes rather than five. Walking the ONU table on a loaded OLT is a
 * far bigger conversation than walking a switch's forty-eight ports — a couple of
 * thousand rows across five columns — and optical power does not move fast enough
 * to be worth asking about more often. What it does is drift over months and then
 * fall off a cliff, and fifteen minutes catches the cliff.
 *
 * <p>Offset from the device monitor's start so the two sweeps do not go out at the
 * same moment down the same uplink.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OntOpticalJob {

    private final OntOpticalService optical;
    private final HeartbeatService heartbeats;

    @Scheduled(fixedDelay = 900_000, initialDelay = 120_000)
    public void run() {
        heartbeats.stamp("ont-optical");
        for (NetworkDevice olt : optical.olts()) {
            if (!olt.isConfigured()) {
                continue; // half-entered; nothing to ask it with
            }
            try {
                OntOpticalService.PollResult result = optical.poll(olt);
                if (result.error() != null) {
                    log.debug("OLT {}: {}", olt.getName(), result.error());
                }
            } catch (Exception e) {
                // One OLT that hangs must not stop the others being read, which
                // is exactly when it matters -- a fibre fault usually shows up on
                // more than one.
                log.warn("ONU sweep failed for {}: {}", olt.getName(), e.getMessage());
            }
        }
    }
}
