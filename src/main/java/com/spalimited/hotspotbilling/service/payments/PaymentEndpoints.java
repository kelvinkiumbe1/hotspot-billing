package com.spalimited.hotspotbilling.service.payments;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where each rail actually lives.
 *
 * <p>These were hardcoded constants inside each provider, which is fine for
 * production and the reason none of the HTTP conversations had ever been
 * exercised: nothing could stand in front of them. Twelve of the thirteen rails
 * have never taken a real payment, so the request building, the headers, the
 * auth and the response parsing were all written against documentation and left
 * unverified.
 *
 * <p>Every default here is the real address, so production behaviour is
 * unchanged and an operator never sets any of this. A test points one at a
 * local server and drives the whole conversation — charge, token, callback,
 * status query — against a stand-in that answers the way the provider's own
 * documentation says it will.
 *
 * <p>Deliberately not per-operator settings. An ISP has no business redirecting
 * where their money is collected, and exposing that in the admin would be a way
 * to steal payments rather than a feature.
 */
@Component
public class PaymentEndpoints {

    @Value("${payments.base-url.paystack:https://api.paystack.co}")
    private String paystack;

    @Value("${payments.base-url.flutterwave:https://api.flutterwave.com/v3}")
    private String flutterwave;

    @Value("${payments.base-url.stripe:https://api.stripe.com/v1}")
    private String stripe;

    @Value("${payments.base-url.mtn-sandbox:https://sandbox.momodeveloper.mtn.com}")
    private String mtnSandbox;

    @Value("${payments.base-url.mtn-production:https://proxy.momoapi.mtn.com}")
    private String mtnProduction;

    @Value("${payments.base-url.airtel-sandbox:https://openapiuat.airtel.africa}")
    private String airtelSandbox;

    @Value("${payments.base-url.airtel-production:https://openapi.airtel.africa}")
    private String airtelProduction;

    @Value("${payments.base-url.orange:https://api.orange.com}")
    private String orange;

    @Value("${payments.base-url.wave:https://api.wave.com}")
    private String wave;

    @Value("${payments.base-url.chapa:https://api.chapa.co/v1}")
    private String chapa;

    @Value("${payments.base-url.paynow:https://www.paynow.co.zw}")
    private String paynow;

    @Value("${payments.base-url.vodacom:https://openapi.m-pesa.com}")
    private String vodacom;

    @Value("${payments.base-url.paymob:https://accept.paymob.com/api}")
    private String paymob;

    @Value("${payments.base-url.konnect-sandbox:https://api.preprod.konnect.network/api/v2}")
    private String konnectSandbox;

    @Value("${payments.base-url.konnect-production:https://api.konnect.network/api/v2}")
    private String konnectProduction;

    /**
     * One address for both environments, because WaafiPay has one.
     *
     * <p>{@code sandbox.waafipay.net} resolves to the same service and answers
     * identically — checked — so test against live is decided by which
     * credentials Hormuud issued, not by which host is called.
     */
    @Value("${payments.base-url.waafipay:https://api.waafipay.net/asm}")
    private String waafipay;

    public String paystack() {
        return paystack;
    }

    public String flutterwave() {
        return flutterwave;
    }

    public String stripe() {
        return stripe;
    }

    /** MTN picks its environment by host, the way Daraja does. */
    public String mtn(boolean live) {
        return live ? mtnProduction : mtnSandbox;
    }

    public String airtel(boolean live) {
        return live ? airtelProduction : airtelSandbox;
    }

    public String orange() {
        return orange;
    }

    public String wave() {
        return wave;
    }

    public String chapa() {
        return chapa;
    }

    public String paynow() {
        return paynow;
    }

    /**
     * Vodacom puts both the environment and the market in the path.
     *
     * <p>Every other rail here varies one or the other; this varies both, and a
     * Tanzanian key against the Mozambican path fails as an authentication
     * error rather than as a wrong address.
     */
    /**
     * Paymob, which is also where the customer is sent.
     *
     * <p>Unlike the others this address appears in a URL a customer opens, not
     * only in server-to-server calls — the checkout is Paymob's own iframe.
     */
    public String paymob() {
        return paymob;
    }

    public String waafipay() {
        return waafipay;
    }

    /** Konnect picks its environment by host, the way Daraja and MTN do. */
    public String konnect(boolean live) {
        return live ? konnectProduction : konnectSandbox;
    }

    public String vodacom(boolean live, String market) {
        return vodacom + (live ? "/openapi" : "/sandbox") + "/ipg/v2/" + market;
    }
}
