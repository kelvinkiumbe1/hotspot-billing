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

    @Transactional(readOnly = true)
    public Optional<PaymentGateway> active() {
        return gateways.findByActiveTrue();
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
     * What to tell a customer who has to pay by hand, or empty when the
     * active gateway collects automatically.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> manualInstructions() {
        PaymentGateway gateway = active().orElse(null);
        if (gateway == null || gateway.isAutomatic() || !gateway.isConfigured()) {
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
            row.put("configured", g != null && g.isConfigured());
            row.put("automatic", kind == PaymentGateway.Kind.MPESA_API);
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
        for (PaymentGateway other : gateways.findAll()) {
            if (other.isActive() && !other.getKind().equals(kind)) {
                other.setActive(false);
                gateways.save(other);
            }
        }
        chosen.setActive(true);
        log.info("Payment gateway switched to {}", kind);
        return gateways.save(chosen);
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
