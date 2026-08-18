package com.spalimited.hotspotbilling.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * How an operator collects money. Credentials live here rather than in
 * environment variables so each operator can set up their own gateway from
 * the admin, without anyone editing a config file and restarting for them.
 *
 * <p>Exactly one gateway is active at a time. The others keep their saved
 * credentials, so switching back is one click rather than a re-entry.
 */
@Entity
@Table(name = "payment_gateways")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentGateway {

    /**
     * Only kinds that genuinely work are here. A gateway that cannot
     * actually take money has no business appearing as an option — that is
     * the failure mode where everything looks fine and nothing collects.
     */
    public enum Kind {
        /** Daraja STK push plus C2B confirmation. Fully automatic. */
        MPESA_API,
        /** A paybill with no API access; payments are reconciled by hand. */
        MPESA_PAYBILL_MANUAL,
        /** A Buy Goods till with no API access; reconciled by hand. */
        MPESA_TILL_MANUAL,
        /** Bank transfer; reconciled by hand. */
        BANK_TRANSFER,
        /**
         * Cards, bank transfer and mobile money across Nigeria, Ghana, Kenya and
         * South Africa. Hosted checkout: the customer opens a URL rather than
         * being prompted on their handset.
         */
        PAYSTACK,
        /** Cards and mobile money across most of Africa. Hosted checkout. */
        FLUTTERWAVE,
        /** Cards worldwide, for operators billing outside mobile-money markets. */
        STRIPE,
        /**
         * MTN Mobile Money across Ghana, Uganda, Rwanda, Zambia, Cameroon and
         * Cote d'Ivoire. Prompts the handset rather than opening a page, so it
         * behaves like M-Pesa rather than like a card processor.
         */
        MTN_MOMO,
        /**
         * Chapa — Ethiopia. Reaches telebirr and the local banks that no
         * pan-African aggregator does. Hosted checkout.
         */
        CHAPA,
        /**
         * Paynow — Zimbabwe. Its Express Checkout prompts an EcoCash or
         * OneMoney handset directly, so it behaves like M-Pesa rather than
         * like a card processor.
         */
        PAYNOW,
        /**
         * Airtel Money across fourteen markets. A USSD push, so it prompts the
         * handset like M-Pesa rather than opening a checkout page.
         */
        AIRTEL_MONEY,
        /**
         * Orange Money across francophone West and Central Africa. Its Web
         * Payment API is a hosted page rather than a handset push, so it sets
         * up like a card processor even though the customer pays from a wallet.
         */
        ORANGE_MONEY,
        /**
         * Wave — Senegal and Cote d'Ivoire. Undercut Orange Money on fees and
         * took real share doing it, so a Senegalese operator wants both.
         * Hosted page, and the only rail here that signs its webhooks properly.
         */
        WAVE
    }

    public enum Environment { SANDBOX, PRODUCTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private Kind kind;

    /**
     * Whether customers may pay through this.
     *
     * <p>No longer exclusive. Several can be true at once, because a Tanzanian
     * ISP has customers on three different wallets and forcing a choice between
     * them means choosing which two thirds of the market cannot pay.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean active = false;

    /**
     * Where this sits in the list a customer is shown.
     *
     * <p>Lowest first, and the lowest is also the default: USSD and the
     * WhatsApp bot have no way to show a picker, so they take whatever comes
     * first rather than refusing to sell.
     */
    @Builder.Default
    @Column(nullable = false)
    private int sortOrder = 100;

    // --- Daraja (MPESA_API only) ---

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private Environment environment = Environment.SANDBOX;

    private String consumerKey;

    private String consumerSecret;

    /** The shortcode STK pushes are billed to. */
    private String shortCode;

    private String passkey;

    /** Initiator username for the Transaction Status API (verifying M-Pesa codes). */
    private String initiatorName;

    /** Initiator password encrypted with Safaricom's public cert; long base64. */
    @Column(length = 2048)
    private String securityCredential;

    // --- Card and pan-African processors ---

    /** Server-side API key. Never leaves the backend. */
    private String secretKey;

    /** Safe for the browser; some checkout flows need it client-side. */
    private String publicKey;

    /**
     * What their webhooks are signed with. Stripe issues a dedicated endpoint
     * secret; Flutterwave compares a hash you choose; Paystack signs with the
     * secret key and needs nothing here.
     */
    private String webhookSecret;

    // --- Manual gateways ---

    /** Paybill number, or the till number for Buy Goods. */
    private String paybillNumber;

    private String tillNumber;

    private String bankName;

    private String accountNumber;

    private String accountName;

    /**
     * What the customer is told on the portal. Free text because every
     * operator words this differently.
     */
    @Column(length = 1000)
    private String instructions;

    private String updatedBy;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = Instant.now();
    }

    /** Whether this gateway has everything it needs to actually collect. */
    @Transient
    public boolean isConfigured() {
        return switch (kind) {
            case MPESA_API -> filled(consumerKey) && filled(consumerSecret)
                    && filled(shortCode) && filled(passkey);
            case MPESA_PAYBILL_MANUAL -> filled(paybillNumber);
            case MPESA_TILL_MANUAL -> filled(tillNumber);
            case BANK_TRANSFER -> filled(bankName) && filled(accountNumber);
            // Paystack signs its webhooks with the secret key, so one field is
            // genuinely enough. Flutterwave and Stripe verify against a secret
            // of their own, and without it the webhook cannot be trusted — so
            // a gateway missing it is not configured, however valid the key is.
            case PAYSTACK -> filled(secretKey);
            case FLUTTERWAVE, STRIPE -> filled(secretKey) && filled(webhookSecret);
            // Subscription key, API user and API key. No webhook secret,
            // because MTN does not sign its callbacks at all — the settlement
            // path re-queries them instead of trusting the body.
            case MTN_MOMO -> filled(secretKey) && filled(consumerKey) && filled(consumerSecret);
            // Chapa signs its webhooks with a secret of its own, so without it
            // the callback cannot be trusted and the gateway is not ready.
            case CHAPA -> filled(secretKey) && filled(webhookSecret);
            // Paynow needs both halves: the id names the merchant, the key
            // salts every hash it sends and checks.
            case PAYNOW -> filled(consumerKey) && filled(secretKey);
            // An OAuth2 client id and secret. Nothing else: the market and its
            // currency follow the operator country rather than being typed in.
            case AIRTEL_MONEY -> filled(consumerKey) && filled(consumerSecret);
            // Three fields, unlike every other OAuth rail here. The client id
            // and secret get a token; the merchant key names which Orange
            // Money merchant the money lands in, and Orange rejects a payment
            // without it. It rides on shortCode because that column already
            // means "the merchant's own identifier at the telco".
            case ORANGE_MONEY -> filled(consumerKey) && filled(consumerSecret) && filled(shortCode);
            // An API key and a webhook secret. Wave signs its callbacks the way
            // Stripe does, so without the secret the callback cannot be trusted
            // and the gateway is not ready however valid the key is.
            case WAVE -> filled(secretKey) && filled(webhookSecret);
        };
    }

    /**
     * True when money arrives without anyone doing anything. The manual
     * kinds still need a person to match a payment to a customer, which is
     * worth saying plainly in the admin rather than implying automation.
     */
    @Transient
    public boolean isAutomatic() {
        return switch (kind) {
            case MPESA_API, PAYSTACK, FLUTTERWAVE, STRIPE, MTN_MOMO, CHAPA, PAYNOW,
                    AIRTEL_MONEY, ORANGE_MONEY, WAVE -> true;
            case MPESA_PAYBILL_MANUAL, MPESA_TILL_MANUAL, BANK_TRANSFER -> false;
        };
    }

    /**
     * Live money, as opposed to a sandbox.
     *
     * <p>Safaricom picks its sandbox by URL, so the stored environment decides.
     * The card processors pick it by which key you paste, which means an
     * operator can believe they are live while every payment is play money.
     * Reading the key's own prefix is the only answer that cannot disagree with
     * what the processor will actually do.
     */
    @Transient
    public boolean isLive() {
        return switch (kind) {
            // Orange picks its sandbox by a segment in the URL, the way Daraja
            // picks it by host, so the stored environment is what decides.
            case MPESA_API, MTN_MOMO, AIRTEL_MONEY, ORANGE_MONEY ->
                    environment == Environment.PRODUCTION;
            // Wave keys carry their own market and mode: wave_sn_prod_… against
            // wave_sn_test_…, so the key is the truth rather than a dropdown an
            // operator can set to the opposite of reality.
            case PAYSTACK, FLUTTERWAVE, STRIPE, CHAPA, WAVE -> !isTestKey(secretKey);
            default -> true;
        };
    }

    /** Paystack and Stripe both prefix test keys "sk_test_"; Flutterwave uses "_TEST". */
    private static boolean isTestKey(String key) {
        if (!filled(key)) {
            return false;
        }
        String k = key.toLowerCase();
        return k.startsWith("sk_test") || k.contains("_test");
    }

    private static boolean filled(String value) {
        return value != null && !value.isBlank();
    }
}
