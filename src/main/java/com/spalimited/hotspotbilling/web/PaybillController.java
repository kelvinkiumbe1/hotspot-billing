package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.PaybillSettings;
import com.spalimited.hotspotbilling.service.PaybillActivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Zero-touch paybill activation: the captive portal asks what to tell this
 * device to pay, and the admin console configures the behaviour.
 */
@RestController
@RequiredArgsConstructor
public class PaybillController {

    private final PaybillActivationService paybill;

    /**
     * The paybill number and the account number this device should type.
     * Public — the customer has not paid yet, so they cannot be authenticated.
     * The MAC comes from the MikroTik hotspot redirect when there is one.
     */
    @GetMapping("/api/paybill/instructions")
    public Map<String, Object> instructions(@RequestParam(required = false) String mac,
                                            @RequestParam(required = false) Long router) {
        return paybill.instructionsFor(mac, router);
    }

    /** Polled by the portal while the customer is at the M-Pesa menu. */
    @GetMapping("/api/paybill/status/{code}")
    public Map<String, Object> status(@PathVariable String code) {
        return paybill.statusOf(code);
    }

    @GetMapping("/api/admin/settings/paybill")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public PaybillSettings getSettings() {
        return paybill.settings();
    }

    @PutMapping("/api/admin/settings/paybill")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public PaybillSettings saveSettings(@RequestBody PaybillSettings in) {
        return paybill.saveSettings(in);
    }
}
