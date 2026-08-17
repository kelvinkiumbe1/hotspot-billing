package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public voucher redemption for the captive portal. Voucher generation
 * and listing live in AdminController.
 */
@RestController
@RequestMapping("/api/vouchers")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;

    /** Called when a customer redeems a code on the portal. */
    @PostMapping("/{code}/activate")
    public Voucher activate(@PathVariable String code) {
        return voucherService.activate(code);
    }

    /**
     * What is left on a pass. Public because the person holding the code is
     * the person entitled to ask, and it returns nothing they do not already
     * have — the code is the credential, exactly as it is at the hotspot login.
     */
    @GetMapping("/{code}/status")
    public VoucherService.PassStatus status(@PathVariable String code) {
        return voucherService.statusOf(voucherService.byCode(code));
    }

    /** Signs the pass out of whatever device is on it, keeping the time. */
    @PostMapping("/{code}/sign-out")
    public Map<String, Object> signOut(@PathVariable String code) {
        Voucher v = voucherService.byCode(code);
        boolean done = voucherService.signOutDevices(v);
        return Map.of("ok", done, "message", done
                ? "Signed out. Your code is free to use on another device."
                : "The router could not be reached just now — nothing was signed out.");
    }

    /**
     * Reissues the pass under a fresh code, carrying the remaining time. For
     * a customer whose code has got out and is being used by other people.
     */
    @PostMapping("/{code}/reissue")
    public VoucherService.PassStatus reissue(@PathVariable String code) {
        return voucherService.statusOf(voucherService.reissueUnderNewCode(voucherService.byCode(code)));
    }
}
