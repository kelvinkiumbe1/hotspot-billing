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
    private final com.spalimited.hotspotbilling.service.PortalSettingsService portalSettings;
    private final com.spalimited.hotspotbilling.service.payments.PublicUrls urls;
    private final com.spalimited.hotspotbilling.service.payments.VodacomMpesaProvider vodacom;

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> all = gatewayService.describeAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateways", all);
        // Every card processor needs a webhook URL pasted into its dashboard,
        // and an operator guessing at it is an operator whose payments never
        // settle. Derived from the M-Pesa callback URL because that is the one
        // address already known to reach this server from the outside.
        out.put("webhookBase", urls.origin());
        // The banks an operator here is likely to hold an account with. A
        // picklist rather than free text, because "Equty Bank" is not a bank
        // and nothing downstream would ever have said so.
        out.put("banks", com.spalimited.hotspotbilling.service.i18n.Banks.forCountry(
                com.spalimited.hotspotbilling.service.i18n.Country.of(
                        portalSettings.settings().getCountry())));
        out.put("available", all.size());
        out.put("connected", all.stream().filter(g -> Boolean.TRUE.equals(g.get("configured"))).count());
        // Kept for anything still reading it, but the plural is the truth now:
        // several gateways can be on at once, and the first is merely the
        // default for the surfaces that cannot ask.
        List<Object> activeKinds = all.stream()
                .filter(g -> Boolean.TRUE.equals(g.get("active")))
                .map(g -> g.get("kind"))
                .toList();
        out.put("activeKinds", activeKinds);
        out.put("activeKind", activeKinds.isEmpty() ? null : activeKinds.get(0));
        out.put("offered", gatewayService.enabled().stream()
                .map(g -> g.getKind().name()).toList());
        return out;
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

    @PostMapping("/{kind}/deactivate")
    public Map<String, Object> deactivate(@PathVariable PaymentGateway.Kind kind, Principal principal) {
        gatewayService.deactivate(kind);
        audit.record(principal, "payments.deactivate", "Customers can no longer pay via " + kind);
        return Map.of("kind", kind, "active", false);
    }

    public record OrderRequest(List<PaymentGateway.Kind> order) {
    }

    /**
     * Reorders the list customers see. The first is also what USSD and the
     * WhatsApp bot use, since neither can show a picker.
     */
    @PutMapping("/order")
    public Map<String, Object> reorder(@RequestBody OrderRequest request, Principal principal) {
        gatewayService.reorder(request.order() == null ? List.of() : request.order());
        audit.record(principal, "payments.reorder", "Changed the order customers see");
        return Map.of("ok", true);
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
    /**
     * Checks a Vodacom M-Pesa setup without taking a payment.
     *
     * <p>Worth its own endpoint because this rail has a failure that looks like
     * success: correct credentials on an app whose C2B product was never
     * enabled. A session opens, the admin shows the gateway as configured, and
     * every customer waits a quarter of an hour before their payment is failed.
     */
    @PostMapping("/VODACOM_MPESA/test")
    public Map<String, Object> testVodacom(Principal principal) {
        String message = vodacom.verify();
        audit.record(principal, "payments.test", "Checked Vodacom M-Pesa credentials");
        return Map.of("message", message);
    }

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
