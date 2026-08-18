package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.MpesaService;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Choosing and configuring how money is collected. Guarded by SETTINGS
 * rather than FINANCE: an accountant reads the money, an owner decides
 * where it lands.
 */
@RestController
@RequestMapping("/api/admin/settings/payments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SETTINGS')")
public class PaymentSettingsController {

    private final PaymentGatewayService gatewayService;
    private final MpesaService mpesaService;
    private final AuditService audit;
    private final com.spalimited.hotspotbilling.config.MpesaProperties mpesaProps;

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> all = gatewayService.describeAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateways", all);
        // Every card processor needs a webhook URL pasted into its dashboard,
        // and an operator guessing at it is an operator whose payments never
        // settle. Derived from the M-Pesa callback URL because that is the one
        // address already known to reach this server from the outside.
        out.put("webhookBase", webhookBase());
        out.put("available", all.size());
        out.put("connected", all.stream().filter(g -> Boolean.TRUE.equals(g.get("configured"))).count());
        out.put("activeKind", all.stream()
                .filter(g -> Boolean.TRUE.equals(g.get("active")))
                .map(g -> g.get("kind"))
                .findFirst().orElse(null));
        return out;
    }

    /** The public origin of this server, or null when it was never configured. */
    private String webhookBase() {
        String callback = mpesaProps.callbackUrl();
        if (callback == null || callback.isBlank() || callback.contains("example.com")) {
            return null;
        }
        int api = callback.indexOf("/api/");
        return api > 0 ? callback.substring(0, api) : null;
    }

    public record GatewayRequest(
            PaymentGateway.Environment environment,
            String consumerKey,
            String consumerSecret,
            String shortCode,
            String passkey,
            String initiatorName,
            String securityCredential,
            String secretKey,
            String publicKey,
            String webhookSecret,
            String paybillNumber,
            String tillNumber,
            String bankName,
            String accountNumber,
            String accountName,
            String instructions) {
    }

    @PutMapping("/{kind}")
    public Map<String, Object> save(@PathVariable PaymentGateway.Kind kind,
                                    @Valid @RequestBody GatewayRequest request,
                                    Principal principal) {
        PaymentGateway incoming = PaymentGateway.builder()
                .kind(kind)
                .environment(request.environment())
                .consumerKey(request.consumerKey())
                .consumerSecret(request.consumerSecret())
                .shortCode(request.shortCode())
                .passkey(request.passkey())
                .initiatorName(request.initiatorName())
                .securityCredential(request.securityCredential())
                .secretKey(request.secretKey())
                .publicKey(request.publicKey())
                .webhookSecret(request.webhookSecret())
                .paybillNumber(request.paybillNumber())
                .tillNumber(request.tillNumber())
                .bankName(request.bankName())
                .accountNumber(request.accountNumber())
                .accountName(request.accountName())
                .instructions(request.instructions())
                .build();

        PaymentGateway saved = gatewayService.save(kind, incoming, principal.getName());
        audit.record(principal, "payments.configure", "Updated " + kind + " settings");
        return Map.of("kind", saved.getKind(), "configured", saved.isConfigured());
    }

    @PostMapping("/{kind}/activate")
    public Map<String, Object> activate(@PathVariable PaymentGateway.Kind kind, Principal principal) {
        PaymentGateway saved = gatewayService.activate(kind);
        audit.record(principal, "payments.activate", "Money is now collected via " + kind
                + (saved.isLive() ? "" : " (SANDBOX — no real money)"));
        return Map.of("kind", saved.getKind(), "active", true, "live", saved.isLive());
    }

    /**
     * Asks Daraja for a token with the stored credentials. Finds a typo
     * during setup instead of when the first customer tries to pay.
     */
    @PostMapping("/MPESA_API/test")
    public Map<String, Object> testDaraja() {
        PaymentGatewayService.DarajaConfig cfg = gatewayService.daraja();
        mpesaService.verifyCredentials(cfg);
        return Map.of(
                "message", "Daraja accepted those credentials",
                "environment", cfg.live() ? "PRODUCTION" : "SANDBOX",
                "warning", cfg.live() ? null
                        : "This is the sandbox — it will not collect real money.");
    }
}
