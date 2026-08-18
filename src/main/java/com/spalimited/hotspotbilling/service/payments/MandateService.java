package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.domain.PaymentMandate;
import com.spalimited.hotspotbilling.domain.Subscriber;
import com.spalimited.hotspotbilling.repository.PaymentMandateRepository;
import com.spalimited.hotspotbilling.repository.SubscriberRepository;
import com.spalimited.hotspotbilling.service.MpesaService;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Standing orders: the customer agrees once and the money arrives monthly.
 *
 * <p>The value is in what stops. Dunning, win-back, expiry nudges and auto-STK
 * all exist to recover a renewal a customer forgot, and every one of them is
 * an interruption the customer did not ask for. A live mandate makes all four
 * unnecessary for that subscriber.
 *
 * <p>Only M-Pesa Ratiba is implemented. The card rails could do the same
 * through tokenisation and deliberately do not yet — a stored card mandate is
 * a different consent with different rules, and pretending one interface covers
 * both would mean charging somebody under an agreement they did not give.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MandateService {

    private final PaymentMandateRepository mandates;
    private final SubscriberRepository subscribers;
    private final PaymentGatewayService gateways;
    private final MpesaService mpesa;

    /**
     * Whether this subscriber's renewals collect themselves.
     *
     * <p>The one question the chasing machinery asks. PENDING deliberately
     * answers no: Ratiba needs the customer to approve on their handset, and a
     * customer who never approved must still be chased or they lapse in silence.
     */
    @Transactional(readOnly = true)
    public boolean collectsAutomatically(Long subscriberId) {
        return mandates.findBySubscriberId(subscriberId)
                .map(PaymentMandate::isCollecting)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public Optional<PaymentMandate> forSubscriber(Long subscriberId) {
        return mandates.findBySubscriberId(subscriberId);
    }

    /**
     * Asks Safaricom to set up a standing order, which the customer then
     * approves on their handset.
     *
     * <p>Stored as PENDING first. If the request succeeds and this row does not
     * exist, the customer has been asked to approve a debit the system has no
     * record of — which is the one failure here that cannot be cleaned up.
     */
    @Transactional
    public PaymentMandate create(Long subscriberId, PaymentMandate.Frequency frequency,
                                 LocalDate startsOn, String by) {
        Subscriber sub = subscribers.findById(subscriberId).orElseThrow(() ->
                new IllegalArgumentException("No such subscriber"));
        if (sub.getMonthlyFee() == null || sub.getMonthlyFee().signum() <= 0) {
            throw new IllegalStateException("Set this customer's monthly fee before a standing order");
        }
        mandates.findBySubscriberId(subscriberId).ifPresent(existing -> {
            if (existing.getStatus() != PaymentMandate.Status.CANCELLED
                    && existing.getStatus() != PaymentMandate.Status.FAILED) {
                throw new IllegalStateException("This customer already has a standing order");
            }
            mandates.delete(existing);
        });

        PaymentGatewayService.DarajaConfig daraja = gateways.daraja();
        if (!daraja.usable()) {
            throw new IllegalStateException("M-Pesa is not set up, so a standing order cannot be "
                    + "created. Add your Daraja credentials under Settings → Payments.");
        }

        PaymentMandate mandate = mandates.save(PaymentMandate.builder()
                .subscriberId(subscriberId)
                .provider(PaymentGateway.Kind.MPESA_API.name())
                .amount(sub.getMonthlyFee())
                .frequency(frequency == null ? PaymentMandate.Frequency.MONTHLY : frequency)
                .startsOn(startsOn == null ? LocalDate.now() : startsOn)
                .status(PaymentMandate.Status.PENDING)
                .createdBy(by)
                .build());
        mandate.setExternalRef(Ratiba.standingOrderName(subscriberId, mandate.getStartsOn()));

        try {
            mpesa.createStandingOrder(mandate, sub.getPhoneNumber());
        } catch (Exception e) {
            // Left FAILED rather than deleted, so the operator can see it was
            // tried and why — a vanished attempt reads as never having tried.
            mandate.setStatus(PaymentMandate.Status.FAILED);
            mandate.setLastError(e.getMessage());
            mandates.save(mandate);
            throw new IllegalStateException("Safaricom would not set up the standing order: "
                    + e.getMessage());
        }
        return mandates.save(mandate);
    }

    /**
     * The customer approved it on their handset. Only now does the chasing stop.
     */
    @Transactional
    public void markActive(String externalRef) {
        mandates.findByExternalRef(externalRef).ifPresent(mandate -> {
            mandate.setStatus(PaymentMandate.Status.ACTIVE);
            mandate.setLastError(null);
            mandates.save(mandate);
            log.info("Standing order {} is now active", externalRef);
        });
    }

    /** Safaricom took money under this mandate. */
    @Transactional
    public void recordCollection(String externalRef) {
        mandates.findByExternalRef(externalRef).ifPresent(mandate -> {
            mandate.setLastCollectedAt(Instant.now());
            mandate.setCollections(mandate.getCollections() + 1);
            // A collection proves it is live, whatever we thought before.
            mandate.setStatus(PaymentMandate.Status.ACTIVE);
            mandates.save(mandate);
        });
    }

    @Transactional
    public void markFailed(String externalRef, String why) {
        mandates.findByExternalRef(externalRef).ifPresent(mandate -> {
            mandate.setStatus(PaymentMandate.Status.FAILED);
            mandate.setLastError(why);
            mandates.save(mandate);
            // Deliberately loud: the operator stopped chasing this customer on
            // the strength of this mandate, and it has just stopped working.
            log.warn("Standing order {} failed: {} — this customer will be chased again",
                    externalRef, why);
        });
    }

    @Transactional
    public void cancel(Long subscriberId, String by) {
        PaymentMandate mandate = mandates.findBySubscriberId(subscriberId).orElseThrow(() ->
                new IllegalArgumentException("This customer has no standing order"));
        mandate.setStatus(PaymentMandate.Status.CANCELLED);
        mandate.setCancelledAt(Instant.now());
        mandates.save(mandate);
        // Said plainly because it is not obvious: Safaricom's own record is not
        // cancelled by this, only ours. The customer stops it from their handset.
        log.info("Standing order for subscriber {} marked cancelled by {}. The customer must also "
                + "stop it on their phone; this only stops us relying on it.", subscriberId, by);
    }

    /**
     * Mandates that claim to be live and have never collected.
     *
     * <p>The dangerous state: the operator has stopped chasing these customers,
     * and the first sign anything is wrong would be them lapsing.
     */
    @Transactional(readOnly = true)
    public List<PaymentMandate> suspect() {
        return mandates.findByStatus(PaymentMandate.Status.ACTIVE).stream()
                .filter(PaymentMandate::isSuspect)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (PaymentMandate.Status status : PaymentMandate.Status.values()) {
            out.put(status.name().toLowerCase(), mandates.countByStatus(status));
        }
        out.put("suspect", suspect().size());
        return out;
    }
}
