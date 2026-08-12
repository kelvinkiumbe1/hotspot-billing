package com.spalimited.hotspotbilling.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Talks to the Zidi control plane to collect this ISP's platform fee. The
 * control plane owns Zidi's M-Pesa and the invoices; this tenant only reports
 * the amount it computed and reads the result back. Server-to-server with a
 * shared token — Zidi's payment credentials never live in a tenant.
 */
@Component
@Slf4j
public class PlatformBillingClient {

    private final String controlUrl;
    private final String token;
    private final String slug;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public PlatformBillingClient(
            @Value("${platform.control-url:}") String controlUrl,
            @Value("${platform.token:}") String token,
            @Value("${tenant.slug:}") String slug) {
        this.controlUrl = controlUrl == null ? "" : controlUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.slug = slug == null ? "" : slug;
    }

    /** True once the platform-billing link has been wired by the provisioner. */
    public boolean configured() {
        return !controlUrl.isBlank() && !token.isBlank() && !slug.isBlank();
    }

    public Map<String, Object> charge(String period, BigDecimal amount, String phone) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "slug", slug, "period", period, "amount", amount, "phone", phone));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(controlUrl + "/api/platform/charge"))
                .header("X-Zidi-Token", token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(40))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req);
    }

    public Map<String, Object> status(String period) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(controlUrl + "/api/platform/" + enc(slug)
                        + "/status?period=" + enc(period)))
                .header("X-Zidi-Token", token)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return send(req);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(HttpRequest req) throws Exception {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> parsed = json.readValue(res.body(), Map.class);
        if (res.statusCode() >= 400) {
            String msg = parsed.get("message") != null ? String.valueOf(parsed.get("message"))
                    : "Platform billing request failed (" + res.statusCode() + ")";
            throw new IllegalStateException(msg);
        }
        return parsed;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
