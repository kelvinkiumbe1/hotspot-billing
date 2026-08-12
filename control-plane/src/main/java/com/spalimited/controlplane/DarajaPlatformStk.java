package com.spalimited.controlplane;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production: collects the platform fee by M-Pesa STK against ZIDI's own
 * Daraja app (paybill/till). Enabled with zidi.platform.provider=DARAJA and the
 * zidi.platform.mpesa.* credentials. Only ever runs on the control-plane host,
 * so Zidi's payment credentials never reach a tenant. (Exercised with live
 * Daraja credentials; local development uses the dry-run provider.)
 *
 * <p>Uses plain strings rather than a JSON library so it stays independent of
 * the framework's Jackson version.
 */
@Component
@ConditionalOnProperty(name = "zidi.platform.provider", havingValue = "DARAJA")
@Slf4j
public class DarajaPlatformStk implements PlatformStk {

    private final String baseUrl;
    private final String consumerKey;
    private final String consumerSecret;
    private final String shortCode;
    private final String passkey;
    private final String callbackUrl;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public DarajaPlatformStk(
            @Value("${zidi.platform.mpesa.base-url:https://sandbox.safaricom.co.ke}") String baseUrl,
            @Value("${zidi.platform.mpesa.consumer-key:}") String consumerKey,
            @Value("${zidi.platform.mpesa.consumer-secret:}") String consumerSecret,
            @Value("${zidi.platform.mpesa.short-code:}") String shortCode,
            @Value("${zidi.platform.mpesa.passkey:}") String passkey,
            @Value("${zidi.platform.mpesa.callback-url:}") String callbackUrl) {
        this.baseUrl = baseUrl;
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.shortCode = shortCode;
        this.passkey = passkey;
        this.callbackUrl = callbackUrl;
    }

    @Override
    public StkResult push(PlatformInvoice invoice) {
        try {
            String token = accessToken();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String password = Base64.getEncoder()
                    .encodeToString((shortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));
            String amount = invoice.getAmount().setScale(0, RoundingMode.HALF_UP).toPlainString();

            String payload = "{"
                    + "\"BusinessShortCode\":\"" + shortCode + "\","
                    + "\"Password\":\"" + password + "\","
                    + "\"Timestamp\":\"" + timestamp + "\","
                    + "\"TransactionType\":\"CustomerPayBillOnline\","
                    + "\"Amount\":\"" + amount + "\","
                    + "\"PartyA\":\"" + invoice.getPhone() + "\","
                    + "\"PartyB\":\"" + shortCode + "\","
                    + "\"PhoneNumber\":\"" + invoice.getPhone() + "\","
                    + "\"CallBackURL\":\"" + callbackUrl + "\","
                    + "\"AccountReference\":\"" + invoice.getTenantSlug() + "\","
                    + "\"TransactionDesc\":\"Zidi platform fee\""
                    + "}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/mpesa/stkpush/v1/processrequest"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            String checkoutId = extract(res.body(), "CheckoutRequestID");
            if (checkoutId == null) {
                String msg = extract(res.body(), "errorMessage");
                if (msg == null) msg = extract(res.body(), "ResponseDescription");
                log.warn("Platform STK for {} rejected: {}", invoice.getTenantSlug(), res.body());
                return StkResult.failed(msg != null ? msg : "STK push rejected");
            }
            return StkResult.ok(checkoutId, "STK sent — awaiting the owner's M-Pesa PIN.");
        } catch (Exception e) {
            log.warn("Platform STK for {} errored: {}", invoice.getTenantSlug(), e.getMessage());
            return StkResult.failed("Could not reach M-Pesa. Try again shortly.");
        }
    }

    private String accessToken() throws Exception {
        String basic = Base64.getEncoder()
                .encodeToString((consumerKey + ":" + consumerSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/oauth/v1/generate?grant_type=client_credentials"))
                .header("Authorization", "Basic " + basic)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return extract(res.body(), "access_token");
    }

    /** Pulls a "key":"value" string field out of a small JSON response. */
    private static String extract(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
