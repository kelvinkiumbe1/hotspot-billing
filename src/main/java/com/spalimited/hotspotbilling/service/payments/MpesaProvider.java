package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.MpesaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * M-Pesa behind the same interface as the card rails.
 *
 * <p>Wrapping it rather than leaving it special-cased is the point: the moment
 * one rail is "the normal one" and the others are alternatives, the normal one
 * accumulates behaviour the others quietly lack. Here it is one of four.
 *
 * <p>Two differences are honest rather than papered over. It returns no
 * checkout URL, because it prompts the handset instead. And its callback does
 * not arrive here — Daraja's shape is different enough that
 * {@code PaymentController} handles it directly, and pretending otherwise
 * would mean translating a callback into a Settlement and back again for no
 * gain.
 */
@Component
@RequiredArgsConstructor
public class MpesaProvider implements PaymentProvider {

    private final MpesaService mpesa;

    @Override
    public PaymentGateway.Kind kind() {
        return PaymentGateway.Kind.MPESA_API;
    }

    @Override
    public boolean usable() {
        return mpesa.canPush();
    }

    @Override
    public Charge charge(ChargeRequest request) {
        String checkoutRequestId = mpesa.stkPush(
                request.phoneNumber(), request.amount(), request.reference());
        // No URL: the customer is already looking at a PIN prompt.
        return new Charge(checkoutRequestId, null);
    }

    @Override
    public Optional<Settlement> settle(byte[] rawBody, Map<String, String> headers) {
        // Daraja posts to its own endpoint, guarded by an IP allowlist rather
        // than a signature, and PaymentController.callback already handles it.
        throw new UnsupportedOperationException(
                "M-Pesa callbacks arrive at /api/payments/mpesa/callback, not here");
    }
}
