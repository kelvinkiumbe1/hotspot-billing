package com.spalimited.controlplane;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zidi-side platform billing. Tenants call {@code /charge} and {@code /status}
 * server-to-server with a shared token (the browser never talks to this
 * directly); Safaricom posts to the public {@code /mpesa/callback}.
 */
@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
@Slf4j
public class PlatformBillingController {

    private final PlatformBillingService billing;

    @Value("${zidi.platform.token:}")
    private String token;

    /** Reject unless the caller presents the shared platform token. */
    private void requireToken(String presented) {
        if (token == null || token.isBlank() || !token.equals(presented)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad platform token");
        }
    }

    public record ChargeRequest(String slug, String period, BigDecimal amount, String phone) {
    }

    @PostMapping("/charge")
    public Map<String, Object> charge(@RequestHeader(value = "X-Zidi-Token", required = false) String presented,
                                      @RequestBody ChargeRequest req) {
        requireToken(presented);
        if (req.slug() == null || req.slug().isBlank()) throw bad("slug required");
        if (req.amount() == null || req.amount().signum() <= 0) throw bad("amount must be positive");
        if (req.phone() == null || !req.phone().matches("254\\d{9}")) throw bad("phone must be 2547XXXXXXXX");
        PlatformInvoice inv = billing.charge(req.slug(), req.period(), req.amount(), req.phone());
        return dto(inv);
    }

    @GetMapping("/{slug}/status")
    public Map<String, Object> status(@RequestHeader(value = "X-Zidi-Token", required = false) String presented,
                                      @PathVariable String slug, @RequestParam String period) {
        requireToken(presented);
        PlatformInvoice inv = billing.latestForPeriod(slug, period);
        return inv == null ? Map.of("status", "NONE", "period", period) : dto(inv);
    }

    @GetMapping("/{slug}/invoices")
    public List<Map<String, Object>> invoices(@RequestHeader(value = "X-Zidi-Token", required = false) String presented,
                                              @PathVariable String slug) {
        requireToken(presented);
        return billing.forTenant(slug).stream().map(PlatformBillingController::dto).toList();
    }

    /** Dry-run/dev only: settle a pending invoice without real M-Pesa. */
    @PostMapping("/invoice/{id}/confirm")
    public Map<String, Object> confirm(@RequestHeader(value = "X-Zidi-Token", required = false) String presented,
                                       @PathVariable Long id) {
        requireToken(presented);
        return dto(billing.confirmManually(id));
    }

    /** Safaricom's STK result. Public — validated by matching the checkout id.
     *  Uses Map binding (not a JSON library) to stay Jackson-version-agnostic. */
    @PostMapping("/mpesa/callback")
    @SuppressWarnings("unchecked")
    public Map<String, String> callback(@RequestBody Map<String, Object> body) {
        Map<String, Object> stk = asMap(asMap(body.get("Body")).get("stkCallback"));
        String checkoutId = str(stk.get("CheckoutRequestID"));
        if (checkoutId == null) return Map.of("ResultCode", "0", "ResultDesc", "ignored");
        int resultCode = stk.get("ResultCode") instanceof Number n ? n.intValue() : -1;
        if (resultCode == 0) {
            String receipt = "PAID";
            Object items = asMap(stk.get("CallbackMetadata")).get("Item");
            if (items instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> item = asMap(o);
                    if ("MpesaReceiptNumber".equals(str(item.get("Name"))) && item.get("Value") != null) {
                        receipt = str(item.get("Value"));
                    }
                }
            }
            billing.markPaid(checkoutId, receipt);
        } else {
            billing.markFailed(checkoutId, str(stk.getOrDefault("ResultDesc", "Payment not completed")));
        }
        return Map.of("ResultCode", "0", "ResultDesc", "ok");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static Map<String, Object> dto(PlatformInvoice inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.getId());
        m.put("slug", inv.getTenantSlug());
        m.put("period", inv.getPeriod());
        m.put("amount", inv.getAmount());
        m.put("status", inv.getStatus().name());
        m.put("detail", inv.getDetail());
        m.put("mpesaReceipt", inv.getMpesaReceipt());
        m.put("paidAt", inv.getPaidAt());
        return m;
    }
}
