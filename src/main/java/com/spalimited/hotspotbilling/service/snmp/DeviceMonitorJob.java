package com.spalimited.hotspotbilling.service.snmp;

import com.spalimited.hotspotbilling.domain.NetworkDevice;
import com.spalimited.hotspotbilling.repository.NetworkDeviceRepository;
import com.spalimited.hotspotbilling.service.HeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Walks every enabled device on a timer.
 *
 * <p>Five minutes rather than the router job's two: an SNMP walk of a 48-port
 * switch is a real conversation, and a site with twenty devices behind one
 * uplink should not spend its bandwidth being asked how it is.
 *
 * <p>Each device is polled in its own transaction, and one that times out is
 * logged and stepped over. A single unreachable box must not stop the other
 * nineteen from being checked — which is exactly when it matters most, because
 * an unreachable box usually means several.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceMonitorJob {

    private final NetworkDeviceRepository devices;
    private final DeviceMonitorService monitor;
    private final HeartbeatService heartbeats;

    @Scheduled(fixedDelay = 300_000, initialDelay = 45_000)
    public void run() {
        heartbeats.stamp("device-monitor");
        for (NetworkDevice device : devices.findByEnabledTrue()) {
            if (!device.isConfigured()) {
                continue; // half-entered; nothing to ask it with
            }
            try {
                monitor.poll(device);
            } catch (Exception e) {
                log.warn("Device poll failed for {}: {}", device.getName(), e.getMessage());
            }
        }
    }
}
