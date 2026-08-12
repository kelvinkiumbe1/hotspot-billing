package com.spalimited.hotspotbilling.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps for payments whose Daraja callback never arrived and settles them by
 * asking Safaricom the real outcome. Without this, a single lost callback
 * leaves a customer charged but never granted access — the delegation to
 * {@link PaymentService#reconcilePending()} is deliberately thin so the money
 * logic stays in one place and stays transactional.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconcileJob {

    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void run() {
        try {
            int settled = paymentService.reconcilePending();
            if (settled > 0) {
                log.info("Reconciled {} payment(s) whose callback had not arrived", settled);
            }
        } catch (Exception e) {
            // Never let a sweep failure kill the schedule; try again next tick.
            log.warn("Payment reconciliation sweep failed: {}", e.getMessage());
        }
    }
}
