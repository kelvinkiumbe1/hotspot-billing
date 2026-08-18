package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import com.spalimited.hotspotbilling.domain.PaymentGateway;
import com.spalimited.hotspotbilling.repository.PaymentGatewayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The operator's chosen way of collecting money.
 *
 * <p>Credentials come from the database when a gateway has been configured
 * in the admin, and fall back to environment variables otherwise. The
 * fallback is what keeps existing deployments working: they were set up
 * with MPESA_* variables and must not stop collecting the day this ships.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentGatewayService {

    private final PaymentGatewayRepository gateways;
    private final MpesaProperties props;

    /** Everything Daraja needs for one call, from whichever source won. */
    public record DarajaConfig(String baseUrl, String consumerKey, String consumerSecret,
                               String shortCode, String passkey, boolean live,
                               String initiatorName, String securityCredential) {

        public boolean usable() {
            return notBlank(consumerKey) && notBlank(consumerSecret)
                    && notBlank(shortCode) && notBlank(passkey);
        }

        /** True when we can verify a pasted M-Pesa code via Transaction Status. */
        public boolean canVerifyTransactions() {
            return usable() && notBlank(initiatorName) && notBlank(securityCredential);
        }

        private static boolean notBlank(String v) {
            return v != null && !v.isBlank();
        }
    }

    private static final String SANDBOX = "https://sandbox.safaricom.co.ke";
    private static final String PRODUCTION = "https://api.safaricom.co.ke";

    /** One gateway by kind, whether or not it is the active one. */
    @Transactional(readOnly = true)
    public Optional<PaymentGateway> find(PaymentGateway.Kind kind) {
        return gateways.findByKind(kind);
    }

    /**
     * Every gateway customers may currently pay through, in the order they are
     * offered.
     */
    @Transactional(readOnly = true)
    public List<PaymentGateway> enabled() {
        return gateways.findAll().stream()
                .filter(PaymentGateway::isActive)
                .filter(PaymentGateway::isConfigured)
                .sorted(java.util.Comparator.comparingInt(PaymentGateway::getSortOrder)
                        .thenComparing(g -> g.getKind().name()))
                .toList();
    }

    /**
     * The one to use when nobody has chosen.
     *
     * <p>USSD and the WhatsApp bot cannot show a picker, so they need an answer
     * rather than a list. The first in the offered order, which for every
     * install that predates multiple gateways is the single one they had.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentGateway> active() {
        return enabled().stream().findFirst();
    }

    /**
     * Daraja settings in force. A configured MPESA_API gateway wins;
     * otherwise the environment variables are used so nothing that works
     * today stops working.
     */
    @Transactional(readOnly = true)
    public DarajaConfig daraja() {
        PaymentGateway db = gateways.findByKind(PaymentGateway.Kind.MPESA_API).orElse(null);
        if (db != null && db.isActive() && db.isConfigured()) {
            return new DarajaConfig(
                    db.getEnvironment() == PaymentGateway.Environment.PRODUCTION ? PRODUCTION : SANDBOX,
                    db.getConsumerKey(), db.getConsumerSecret(),
                    db.getShortCode(), db.getPasskey(),
                    db.getEnvironment() == PaymentGateway.Environment.PRODUCTION,
                    db.getInitiatorName(), db.getSecurityCredential());
        }
        return new DarajaConfig(props.baseUrl(), props.consumerKey(), props.consumerSecret(),
                props.shortCode(), props.passkey(),
                props.baseUrl() != null && props.baseUrl().contains("api.safaricom"),
                null, null);
    }

    /** True when an STK push can actually be sent right now. */
    @Transactional(readOnly = true)
    public boolean stkAvailable() {
        return daraja().usable();
    }

    /** True when a pasted M-Pesa code can be verified via Transaction Status. */
    @Transactional(readOnly = true)
    public boolean transactionStatusAvailable() {
        return daraja().canVerifyTransactions();
    }

    private static boolean filledForVerify(PaymentGateway g) {
        return g.getInitiatorName() != null && !g.getInitiatorName().isBlank()
                && g.getSecurityCredential() != null && !g.getSecurityCredential().isBlank();
    }

    /**
     * What to tell a customer who has to pay by hand, or empty when there is no
     * hand-reconciled way to pay.
     *
     * <p>Looks for the first <em>manual</em> gateway rather than the first
     * enabled one. Now that several can be on at once, the first is often M-Pesa
     * STK — and reading only that would report "no payment details" to a
     * customer while a perfectly good paybill sat switched on behind it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> manualInstructions() {
        PaymentGateway gateway = enabled().stream()
                .filter(g -> !g.isAutomatic())
                .findFirst().orElse(null);
        if (gateway == null || !gateway.isConfigured()) {
            // Fall back to a paybill set by environment variable, which is
            // how the current deployments show one.
            if (props.paybill() != null && !props.paybill().isBlank()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("kind", "MPESA_PAYBILL_MANUAL");
                out.put("paybillNumber", props.paybill());
                out.put("instructions", null);
                return out;
            }
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", gateway.getKind().name());
        out.put("paybillNumber", gateway.getPaybillNumber());
        out.put("tillNumber", gateway.getTillNumber());
        out.put("bankName", gateway.getBankName());
        out.put("accountNumber", gateway.getAccountNumber());
        out.put("accountName", gateway.getAccountName());
        out.put("instructions", gateway.getInstructions());
        out.values().removeIf(Objects::isNull);
        return out;
    }

    /**
     * Every hand-reconciled way to pay, for a portal that can show more than one.
     *
     * <p>An operator may well have a paybill and a bank account both switched
     * on; showing only whichever happened to be first is the sort of thing a
     * customer reads as the business not knowing its own payment details.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> allManualInstructions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PaymentGateway gateway : enabled()) {
            if (gateway.isAutomatic()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", gateway.getKind().name());
            row.put("paybillNumber", gateway.getPaybillNumber());
            row.put("tillNumber", gateway.getTillNumber());
            row.put("bankName", gateway.getBankName());
            row.put("accountNumber", gateway.getAccountNumber());
            row.put("accountName", gateway.getAccountName());
            row.put("instructions", gateway.getInstructions());
            row.values().removeIf(Objects::isNull);
            out.add(row);
        }
        return out;
    }

    /** Every gateway with its state, secrets masked. */
    @Transactional
    public List<Map<String, Object>> describeAll() {
        Map<PaymentGateway.Kind, PaymentGateway> saved = new EnumMap<>(PaymentGateway.Kind.class);
        gateways.findAll().forEach(g -> saved.put(g.getKind(), g));

        List<Map<String, Object>> out = new ArrayList<>();
        for (PaymentGateway.Kind kind : PaymentGateway.Kind.values()) {
            PaymentGateway g = saved.get(kind);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind.name());
            row.put("active", g != null && g.isActive());
            row.put("sortOrder", g != null ? g.getSortOrder() : 100);
            row.put("configured", g != null && g.isConfigured());
            row.put("automatic", PaymentGateway.builder().kind(kind).build().isAutomatic());
            row.put("environment", g != null && g.getEnvironment() != null
                    ? g.getEnvironment().name() : PaymentGateway.Environment.SANDBOX.name());
            row.put("live", g != null && g.isLive());
            row.put("updatedAt", g != null ? g.getUpdatedAt() : null);
            row.put("updatedBy", g != null ? g.getUpdatedBy() : null);

            // Never hand a secret back out, even to an owner. Enough
            // characters to recognise it, not enough to use it.
            row.put("consumerKey", mask(g == null ? null : g.getConsumerKey()));
            row.put("consumerSecret", mask(g == null ? null : g.getConsumerSecret()));
            row.put("passkey", mask(g == null ? null : g.getPasskey()));
            // Initiator name isn't a secret; the credential is.
            row.put("initiatorName", g == null ? null : g.getInitiatorName());
            row.put("securityCredential", mask(g == null ? null : g.getSecurityCredential()));
            row.put("canVerifyCodes", g != null && filledForVerify(g));

            row.put("secretKey", mask(g == null ? null : g.getSecretKey()));
            row.put("webhookSecret", mask(g == null ? null : g.getWebhookSecret()));
            // The public key is public by design — masking it would only make
            // an operator go and look it up again to check they pasted the right one.
            row.put("publicKey", g == null ? null : g.getPublicKey());

            row.put("shortCode", g == null ? null : g.getShortCode());
            row.put("paybillNumber", g == null ? null : g.getPaybillNumber());
            row.put("tillNumber", g == null ? null : g.getTillNumber());
            row.put("bankName", g == null ? null : g.getBankName());
            row.put("accountNumber", g == null ? null : g.getAccountNumber());
            row.put("accountName", g == null ? null : g.getAccountName());
            row.put("instructions", g == null ? null : g.getInstructions());
            out.add(row);
        }
        return out;
    }

    /** Shows a credential exists without revealing it: "••••••••7f2a". */
    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        String tail = secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
        return "••••••••" + tail;
    }

    /**
     * Saves one gateway's settings. A blank secret means "leave the stored
     * one alone", so an operator can edit the shortcode without retyping
     * credentials they can no longer read.
     */
    @Transactional
    public PaymentGateway save(PaymentGateway.Kind kind, PaymentGateway incoming, String updatedBy) {
        PaymentGateway gateway = gateways.findByKind(kind)
                .orElseGet(() -> PaymentGateway.builder().kind(kind).build());

        if (kind == PaymentGateway.Kind.MPESA_API) {
            gateway.setEnvironment(incoming.getEnvironment() != null
                    ? incoming.getEnvironment() : PaymentGateway.Environment.SANDBOX);
            gateway.setShortCode(trim(incoming.getShortCode()));
            keepIfBlank(incoming.getConsumerKey(), gateway::getConsumerKey, gateway::setConsumerKey);
            keepIfBlank(incoming.getConsumerSecret(), gateway::getConsumerSecret, gateway::setConsumerSecret);
            keepIfBlank(incoming.getPasskey(), gateway::getPasskey, gateway::setPasskey);
            keepIfBlank(incoming.getInitiatorName(), gateway::getInitiatorName, gateway::setInitiatorName);
            keepIfBlank(incoming.getSecurityCredential(), gateway::getSecurityCredential, gateway::setSecurityCredential);
        } else if (PaymentGateway.builder().kind(kind).build().isAutomatic()) {
            // Every remaining automatic rail. Written as one branch rather than
            // a list of kinds because the list was the bug: MTN MoMo, Airtel,
            // Chapa and Paynow all fell through to the manual branch below,
            // which stores paybill numbers and silently discards API
            // credentials — so none of the four could ever be configured, and
            // the settings screen looked like it had saved them.
            keepIfBlank(incoming.getSecretKey(), gateway::getSecretKey, gateway::setSecretKey);
            keepIfBlank(incoming.getPublicKey(), gateway::getPublicKey, gateway::setPublicKey);
            keepIfBlank(incoming.getWebhookSecret(), gateway::getWebhookSecret, gateway::setWebhookSecret);
            keepIfBlank(incoming.getConsumerKey(), gateway::getConsumerKey, gateway::setConsumerKey);
            keepIfBlank(incoming.getConsumerSecret(), gateway::getConsumerSecret, gateway::setConsumerSecret);
            // The telco rails pick their sandbox by URL the way Daraja does, so
            // they need the stored environment. The card rails read it off the
            // key prefix and simply ignore this.
            if (incoming.getEnvironment() != null) {
                gateway.setEnvironment(incoming.getEnvironment());
            }
        } else {
            gateway.setPaybillNumber(trim(incoming.getPaybillNumber()));
            gateway.setTillNumber(trim(incoming.getTillNumber()));
            gateway.setBankName(trim(incoming.getBankName()));
            gateway.setAccountNumber(trim(incoming.getAccountNumber()));
            gateway.setAccountName(trim(incoming.getAccountName()));
        }
        gateway.setInstructions(trim(incoming.getInstructions()));
        gateway.setUpdatedBy(updatedBy);
        return gateways.save(gateway);
    }

    /**
     * Makes one gateway the active one and stands the others down. Refuses
     * a gateway that is not configured — activating it would leave the
     * operator collecting nothing while the admin looked healthy.
     */
    @Transactional
    public PaymentGateway activate(PaymentGateway.Kind kind) {
        PaymentGateway chosen = gateways.findByKind(kind).orElseThrow(() ->
                new IllegalArgumentException("Set that gateway up before switching to it"));
        if (!chosen.isConfigured()) {
            throw new IllegalStateException("That gateway is missing details it needs — finish "
                    + "setting it up before making it active");
        }
        // Deliberately does not stand the others down any more. Several wallets
        // live side by side in most markets, and switching one on used to switch
        // the rest off — which for a Tanzanian operator meant two thirds of
        // their customers losing the ability to pay.
        chosen.setActive(true);
        if (chosen.getSortOrder() >= 100) {
            // Newly switched on goes to the end of the list rather than jumping
            // ahead of whatever the operator already had customers using.
            int lowest = enabled().stream()
                    .mapToInt(PaymentGateway::getSortOrder).max().orElse(0);
            chosen.setSortOrder(Math.min(99, lowest + 10));
        }
        log.info("Payment gateway {} switched on", kind);
        return gateways.save(chosen);
    }

    /**
     * Stops customers being offered this one.
     *
     * <p>Refuses to switch off the last one: an operator with nothing enabled
     * cannot be paid, and the admin would look healthy while every sale failed.
     */
    @Transactional
    public PaymentGateway deactivate(PaymentGateway.Kind kind) {
        PaymentGateway chosen = gateways.findByKind(kind).orElseThrow(() ->
                new IllegalArgumentException("That gateway is not set up"));
        if (enabled().size() <= 1 && chosen.isActive()) {
            throw new IllegalStateException("That is the only way customers can pay you. "
                    + "Switch another one on before turning this off.");
        }
        chosen.setActive(false);
        log.info("Payment gateway {} switched off", kind);
        return gateways.save(chosen);
    }

    /** Reorders the list customers see. The first is also the default. */
    @Transactional
    public void reorder(List<PaymentGateway.Kind> order) {
        int position = 10;
        for (PaymentGateway.Kind kind : order) {
            PaymentGateway gateway = gateways.findByKind(kind).orElse(null);
            if (gateway != null) {
                gateway.setSortOrder(position);
                gateways.save(gateway);
                position += 10;
            }
        }
    }

    private static void keepIfBlank(String incoming, java.util.function.Supplier<String> current,
                                    java.util.function.Consumer<String> setter) {
        if (incoming != null && !incoming.isBlank() && !incoming.startsWith("••••")) {
            setter.accept(incoming.trim());
        } else if (current.get() == null) {
            setter.accept(null);
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
