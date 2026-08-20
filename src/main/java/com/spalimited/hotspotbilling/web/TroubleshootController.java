package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.HotspotTroubleshooter;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Why one customer cannot get online.
 *
 * <p>Under CUSTOMERS rather than NETWORK on purpose: the person who needs this is
 * whoever answered the phone, and they are the role least likely to have network
 * permissions.
 */
@RestController
@RequestMapping("/api/admin/troubleshoot")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class TroubleshootController {

    private final HotspotTroubleshooter troubleshooter;

    @GetMapping("/hotspot")
    public Map<String, Object> hotspot(@RequestParam @Size(max = 64) String code,
                                       @RequestParam(required = false) @Size(max = 32) String mac) {
        return troubleshooter.diagnose(code, mac);
    }
}
