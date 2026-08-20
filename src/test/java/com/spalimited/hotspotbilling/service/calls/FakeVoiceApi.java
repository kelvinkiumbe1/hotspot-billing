package com.spalimited.hotspotbilling.service.calls;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A voice provider that isn't one.
 *
 * <p>The same idea as FakeGateway on the payments side, and for the same reason:
 * placing a call has never been done once against the real API, so the request we
 * build -- its URL, its form encoding, its apiKey header -- has never been
 * executed. A test that mocks the HTTP client proves the parsing and nothing
 * about the request that produced it, which is exactly the gap that hid the
 * Airtel bug and the stripped Origin header on Vodacom.
 *
 * <p>A real socket, therefore. What this cannot prove is that the provider
 * accepts what we send; only that we send what we meant to.
 */
final class FakeVoiceApi implements AutoCloseable {

    /** What was actually sent, so a test can assert on it. */
    record Call(String method, String path, Map<String, String> headers, String body) {

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        /** One form field, decoded. */
        String field(String name) {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new ArrayList<>();
    private final Map<String, Reply> replies = new LinkedHashMap<>();

    private record Reply(int status, String body) {
    }

    FakeVoiceApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake voice api", e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
            synchronized (calls) {
                calls.add(new Call(exchange.getRequestMethod(), path, headers, body));
            }
            Reply reply = replies.get(path);
            if (reply == null) {
                // 418 rather than 404, so a call to the wrong path cannot be
                // mistaken for the provider's own "not found".
                reply = new Reply(418, "{\"errorMessage\":\"no route for " + path + "\"}");
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    FakeVoiceApi on(String path, String json) {
        return on(path, 200, json);
    }

    FakeVoiceApi on(String path, int status, String json) {
        replies.put(path, new Reply(status, json));
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Call> calls() {
        synchronized (calls) {
            return List.copyOf(calls);
        }
    }

    Call call(String path) {
        return calls().stream()
                .filter(c -> c.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing was sent to " + path
                        + "; calls were: " + calls().stream()
                        .map(c -> c.method() + " " + c.path()).toList()));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
