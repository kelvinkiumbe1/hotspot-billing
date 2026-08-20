package com.spalimited.hotspotbilling.service.acs;

import com.spalimited.hotspotbilling.domain.CpeDevice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Poking a router into calling home now.
 *
 * <p>The ACS can only answer, never ask — so without this, a change waits for the
 * device's periodic Inform, which is typically an hour. An hour is long enough
 * that an operator on the phone to a customer gives up and talks them through the
 * router's own web interface instead, which is the thing this whole feature
 * exists to avoid.
 *
 * <p>A connection request is a bare GET to a URL the device reported, answered
 * with HTTP Digest. Digest rather than Basic because that is what the TR-069
 * specification requires and what every CPE implements — sending Basic gets a
 * 401 that looks like wrong credentials.
 *
 * <p>Never throws. A CPE behind carrier-grade NAT reports a URL nothing outside
 * can reach, and that is a normal state of affairs rather than an error: it means
 * the change happens at the next check-in instead of now, and the operator is
 * told exactly that.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectionRequest {

    /**
     * Short on purpose.
     *
     * <p>Somebody is waiting on a screen for this. A CPE that is reachable
     * answers immediately; one that does not answer in five seconds is behind
     * something, and waiting thirty seconds to learn that helps nobody.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Result(boolean reached, String detail) {

        static Result ok() {
            return new Result(true, "the router answered");
        }

        public static Result unreachable(String why) {
            return new Result(false, why);
        }
    }

    /** Asks the device to start a session. */
    public Result poke(CpeDevice device) {
        String url = device.getConnectionRequestUrl();
        if (url == null || url.isBlank()) {
            return Result.unreachable("it has not told us where to reach it");
        }
        try {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    // The 401 carrying the digest challenge is the normal first
                    // answer, not a redirect to follow.
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

            HttpResponse<String> first = send(http, url, null);
            // A CPE that wants no authentication answers 200 straight away, and a
            // few do. 204 counts too -- the specification allows it and some
            // firmware uses it.
            if (first.statusCode() == 200 || first.statusCode() == 204) {
                return Result.ok();
            }
            if (first.statusCode() != 401) {
                return Result.unreachable("it answered " + first.statusCode());
            }

            String challenge = first.headers().firstValue("WWW-Authenticate").orElse(null);
            if (challenge == null) {
                return Result.unreachable("it asked for credentials but did not say how");
            }
            if (device.getConnectionRequestUsername() == null
                    || device.getConnectionRequestUsername().isBlank()) {
                return Result.unreachable("it wants credentials and none are saved for it");
            }
            HttpResponse<String> second = send(http, url,
                    digest(challenge, url, device.getConnectionRequestUsername(),
                            device.getConnectionRequestPassword()));
            if (second.statusCode() == 200 || second.statusCode() == 204) {
                return Result.ok();
            }
            return Result.unreachable("it refused the credentials (" + second.statusCode() + ")");
        } catch (Exception e) {
            // Behind NAT, asleep, on a different network, powered off. All normal.
            log.debug("Could not reach CPE {}: {}", device.getSerialNumber(), e.getMessage());
            return Result.unreachable("it could not be reached");
        }
    }

    private static HttpResponse<String> send(HttpClient http, String url, String authorization)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * An HTTP Digest response to the device's challenge.
     *
     * <p>Hand-built because the JDK's own authenticator does not do digest over
     * plain HTTP without a system property that would apply to every request this
     * process makes, including ones to payment gateways. A dozen lines here is
     * better than a global switch.
     */
    static String digest(String challenge, String url, String username, String password) {
        Map<String, String> fields = parseChallenge(challenge);
        String realm = fields.getOrDefault("realm", "");
        String nonce = fields.getOrDefault("nonce", "");
        String qop = fields.get("qop");
        String opaque = fields.get("opaque");
        String uri = URI.create(url).getRawPath();
        if (uri == null || uri.isBlank()) {
            uri = "/";
        }
        String pass = password == null ? "" : password;

        String ha1 = md5(username + ":" + realm + ":" + pass);
        String ha2 = md5("GET:" + uri);
        String response;
        String cnonce = null;
        String nc = null;
        if (qop != null && !qop.isBlank()) {
            // qop may be offered as "auth,auth-int"; we only do auth, and saying
            // so explicitly is required rather than echoing the whole list.
            cnonce = HexFormat.of().formatHex(randomBytes());
            nc = "00000001";
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
        }

        StringBuilder header = new StringBuilder("Digest ");
        header.append("username=\"").append(username).append("\", ");
        header.append("realm=\"").append(realm).append("\", ");
        header.append("nonce=\"").append(nonce).append("\", ");
        header.append("uri=\"").append(uri).append("\", ");
        header.append("response=\"").append(response).append("\"");
        if (qop != null && !qop.isBlank()) {
            header.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce)
                    .append("\"");
        }
        if (opaque != null) {
            header.append(", opaque=\"").append(opaque).append("\"");
        }
        return header.toString();
    }

    /** Pulls the quoted fields out of a WWW-Authenticate header. */
    static Map<String, String> parseChallenge(String challenge) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (challenge == null) {
            return fields;
        }
        String body = challenge.trim();
        if (body.toLowerCase(Locale.ROOT).startsWith("digest ")) {
            body = body.substring(7);
        }
        // Split on commas that are not inside quotes, because a realm may
        // legitimately contain one and splitting naively loses everything after it.
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (char c : body.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            }
            if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());

        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            fields.put(key, value);
        }
        return fields;
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * MD5, because HTTP Digest specifies it.
     *
     * <p>Not a security choice and not one available to make differently: the CPE
     * decides the algorithm and every one of them says MD5. It is authenticating a
     * "please call home" request on a local network, not protecting anything.
     */
    private static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }
}
