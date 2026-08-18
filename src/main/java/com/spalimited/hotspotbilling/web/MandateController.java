package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PaymentMandate;
import com.spalimited.hotspotbilling.service.AuditService;
import com.spalimited.hotspotbilling.service.SubscriptionService;
import com.spalimited.hotspotbilling.service.payments.MandateService;
import com.spalimited.hotspotbilling.service.payments.PaymentProvider;
import com.spalimited.hotspotbilling.service.payments.PaymentProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standing orders, so a renewal collects itself.
 *
 * <p>Nothing exposed these before. {@code MandateService} has been able to set
 * up M-Pesa Ratiba for some time and there was no way to ask it to — which is
 * its own kind of gap: the code existed and the feature did not.
 *
 * <p>Guarded by CUSTOMERS rather than FINANCE, matching SubscriberController.
 * Arranging how a customer pays is the work of whoever manages that customer;
 * reading the money is a different job.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('CUSTOMERS')")
public class MandateController {

    private final MandateService mandates;
    private final SubscriptionService subscriptions;
    private final PaymentProviders providers;
    private final AuditService audit;

    /**
     * Which rails can hold a standing order right now, and how each one asks.
     *
     * <p>The admin needs this to know what to offer. A rail that cannot charge
     * again must not appear as an option — the operator would set it up, stop
     * chasing that customer, and collect nothing.
     */
    @GetMapping("/mandates/options")
    public Map<String, Object> options() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (PaymentProvider provider : providers.enabled()) {
            boolean ratiba = provider.kind() == PaymentGateway.Kind.MPESA_API;
            if (!ratiba && !provider.supportsRecurring()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", provider.kind().name());
            row.put("model", ratiba ? "PUSH" : "PULL");
            row.put("how", ratiba
                    ? "The customer approves a standing order on their handset, and Safaricom "
                            + "sends the money each month."
                    : "The customer pays this month as usual and agrees that renewals may be "
                            + "charged the same way. Send them the link this returns.");
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("options", out);
        result.put("summary", mandates.summary());
        return result;
    }

    @GetMapping("/subscribers/{id}/mandate")
    public Map<String, Object> forSubscriber(@PathVariable Long id) {
        return mandates.forSubscriber(id).map(MandateController::describe)
                .orElseGet(() -> Map.of("exists", false));
    }

    public record SetUpRequest(PaymentGateway.Kind kind, PaymentMandate.Frequency frequency,
                               LocalDate startsOn) {
    }

    /**
     * Sets one up, by whichever mechanism the chosen rail uses.
     *
     * <p>Ratiba asks Safaricom and the customer approves on their handset. Every
     * other rail needs a real payment to authorise against, so this starts one:
     * the customer pays this month's fee and that payment carries back the
     * authorisation for the ones after it. Two things at once, deliberately —
     * asking somebody to authorise a card without charging anything is a worse
     * experience and, on most of these rails, not actually possible.
     */
    @PostMapping("/subscribers/{id}/mandate")
    public Map<String, Object> setUp(@PathVariable Long id, @RequestBody SetUpRequest request,
                                     Principal principal) {
        PaymentGateway.Kind kind = request.kind() == null
                ? PaymentGateway.Kind.MPESA_API : request.kind();

        if (kind == PaymentGateway.Kind.MPESA_API) {
            PaymentMandate mandate = mandates.create(id, request.frequency(),
                    request.startsOn(), principal.getName());
            audit.record(principal, "mandate.create",
                    "Asked subscriber " + id + " to approve an M-Pesa standing order");
            Map<String, Object> out = new LinkedHashMap<>(describe(mandate));
            out.put("message", "Ask the customer to approve the standing order on their phone. "
                    + "Until they do, they will still be chased for this month.");
            return out;
        }

        PaymentProvider provider = providers.chosen(kind.name())
                .filter(PaymentProvider::supportsRecurring)
                .orElseThrow(() -> new IllegalStateException(
                        kind + " cannot hold a standing order. Switch it on under Settings → "
                                + "Payments first, and check it is a rail that can charge again."));

        // The payment first: its id is what the mandate is authorised against,
        // and a mandate pointing at a payment that was never started would sit
        // PENDING forever with nobody able to say why.
        SubscriptionService.Started started = subscriptions.initiateRenewal(id, 1, kind.name());
        String reference = SubscriptionService.referenceFor(id, started.payment().getId());
        PaymentMandate mandate = mandates.awaitConsent(id, provider.kind(), reference,
                principal.getName());

        audit.record(principal, "mandate.create",
                "Asked subscriber " + id + " to authorise " + kind + " renewals");
        Map<String, Object> out = new LinkedHashMap<>(describe(mandate));
        out.put("checkoutUrl", started.checkoutUrl());
        out.put("message", started.checkoutUrl() == null
                ? "Ask the customer to complete the payment on their phone. Renewals start "
                        + "collecting once it goes through."
                : "Send the customer this link. Once they pay, renewals collect themselves.");
        return out;
    }

    @DeleteMapping("/subscribers/{id}/mandate")
    public Map<String, Object> cancel(@PathVariable Long id, Principal principal) {
        mandates.cancel(id, principal.getName());
        audit.record(principal, "mandate.cancel",
                "Stopped relying on subscriber " + id + "'s standing order");
        return Map.of("cancelled", true,
                "message", "This customer will be asked to pay again from the next renewal.");
    }

    /**
     * The ones that claim to be live and are not working.
     *
     * <p>Worth its own list. The operator has stopped chasing these customers,
     * and without this the first sign of trouble is them lapsing.
     */
    @GetMapping("/mandates/suspect")
    public List<Map<String, Object>> suspect() {
        return mandates.suspect().stream().map(MandateController::describe).toList();
    }

    /** Never includes the token. It is the thing that can move a customer's money. */
    private static Map<String, Object> describe(PaymentMandate m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("exists", true);
        row.put("id", m.getId());
        row.put("subscriberId", m.getSubscriberId());
        row.put("provider", m.getProvider());
        row.put("model", m.getModel().name());
        row.put("status", m.getStatus().name());
        row.put("amount", m.getAmount());
        row.put("frequency", m.getFrequency().name());
        row.put("startsOn", m.getStartsOn());
        row.put("collections", m.getCollections());
        row.put("lastCollectedAt", m.getLastCollectedAt());
        row.put("lastAttemptAt", m.getLastAttemptAt());
        row.put("consecutiveFailures", m.getConsecutiveFailures());
        row.put("consentedAt", m.getConsentedAt());
        row.put("lastError", m.getLastError());
        // Whether it is doing the job, which is not the same as its status.
        row.put("collecting", m.isCollecting());
        row.put("weCollect", m.weCollect());
        row.put("suspect", m.isSuspect());
        return row;
    }
}
