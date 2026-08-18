package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.service.PaymentGatewayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Picks the rail a payment should go down.
 *
 * <p>The operator marks one gateway active; this finds the code that speaks to
 * it. Everything else in the system asks for "the way we take money" and gets
 * an answer, rather than each caller knowing that M-Pesa exists.
 */
@Service
@Slf4j
public class PaymentProviders {

    private final List<PaymentProvider> providers;
    private final PaymentGatewayService gateways;

    public PaymentProviders(List<PaymentProvider> providers, PaymentGatewayService gateways) {
        this.providers = providers;
        this.gateways = gateways;
    }

    /**
     * The provider for whichever gateway is active and usable.
     *
     * <p>Empty when the active gateway is one of the manual kinds — a paybill
     * or a bank account nobody automated — because there is genuinely nothing
     * to call. Callers turn that into "pay by hand and quote this reference",
     * which is a real answer rather than an error.
     */
    /**
     * Every rail a customer may choose right now, in the order to offer them.
     *
     * <p>Only the automatic ones: a bank transfer is a set of instructions, not
     * something this can charge, and putting it in the same list as M-Pesa would
     * have the portal try to start a payment it cannot start.
     */
    public List<PaymentProvider> enabled() {
        return gateways.enabled().stream()
                .filter(PaymentGateway::isAutomatic)
                .map(PaymentGateway::getKind)
                .flatMap(kind -> forKind(kind).stream())
                .filter(PaymentProvider::usable)
                .toList();
    }

    /**
     * The rail to use when the customer has not chosen one.
     *
     * <p>USSD and the WhatsApp bot cannot show a picker. They get the first
     * rather than an error, because a sale through the wrong-but-working wallet
     * beats no sale at all.
     */
    public Optional<PaymentProvider> active() {
        return enabled().stream().findFirst();
    }

    /**
     * The rail the customer asked for, if it is one they are allowed to use.
     *
     * <p>Checked against the enabled list rather than merely resolved by name:
     * without that, a crafted request could charge through a gateway the
     * operator has deliberately switched off.
     */
    public Optional<PaymentProvider> chosen(String kind) {
        if (kind == null || kind.isBlank()) {
            return active();
        }
        return forKind(kind).filter(p -> enabled().contains(p));
    }

    public Optional<PaymentProvider> forKind(PaymentGateway.Kind kind) {
        return providers.stream().filter(p -> p.kind() == kind).findFirst();
    }

    /**
     * The same, from the name stored on a payment.
     *
     * <p>Payments record the rail as text, and rows written before a rail
     * existed have none at all. An unreadable name is empty rather than an
     * exception: reconciliation runs over every pending payment, and one
     * unrecognisable row must not stop the sweep.
     */
    public Optional<PaymentProvider> forKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return Optional.empty();
        }
        try {
            return forKind(PaymentGateway.Kind.valueOf(kind.trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Every rail this build can drive, for the settings screen to offer. */
    public List<PaymentGateway.Kind> supported() {
        return providers.stream().map(PaymentProvider::kind).sorted().toList();
    }
}
