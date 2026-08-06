package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.repository.PaymentRepository;
import com.spalimited.hotspotbilling.repository.VoucherRepository;
import com.spalimited.hotspotbilling.service.SmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SMS marketing campaigns to everyone who has ever bought or redeemed a
 * voucher (HTTP Basic, ADMIN role).
 */
@RestController
@RequestMapping("/api/admin/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;
    private final PaymentRepository payments;
    private final VoucherRepository vouchers;

    private Set<String> recipients() {
        return Stream.concat(
                        payments.findAll().stream().map(p -> p.getPhoneNumber()),
                        vouchers.findAll().stream().map(v -> v.getPhoneNumber()))
                .filter(Objects::nonNull)
                .filter(p -> p.matches("254\\d{9}"))
                .collect(Collectors.toSet());
    }

    /** How many customers a campaign would reach, and whether SMS is configured. */
    @GetMapping("/recipients")
    public Map<String, Object> preview() {
        return Map.of("count", recipients().size(), "enabled", smsService.isEnabled());
    }

    public record CampaignRequest(@NotBlank @Size(max = 320) String message) {
    }

    @PostMapping("/campaign")
    public Map<String, Object> campaign(@Valid @RequestBody CampaignRequest request) {
        int sent = smsService.sendBulk(recipients(), request.message());
        return Map.of("sent", sent, "message", "Campaign submitted to " + sent + " customers");
    }
}
