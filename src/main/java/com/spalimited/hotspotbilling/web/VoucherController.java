package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Voucher;
import com.spalimited.hotspotbilling.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
