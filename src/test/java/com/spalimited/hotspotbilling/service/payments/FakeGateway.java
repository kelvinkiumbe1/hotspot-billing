package com.spalimited.hotspotbilling.service.payments;

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
 * A payment provider that isn't one.
 *
 * <p>Twelve of the thirteen rails have never taken a real payment. Their request
 * building, headers, auth and response parsing were written against
 * documentation and never once executed, and unit tests do not touch any of it —
 * they call {@code read()} on a JsonNode somebody typed by hand, which proves
 * the parsing and nothing about the request that produced it.
 *
 * <p>This is a real HTTP server on a real socket. A provider is pointed at it
 * through {@link PaymentEndpoints} and drives its whole conversation against
 * canned responses in the shape the provider's own documentation specifies. It
 * catches the class of bug that killed Airtel: a request assembled wrongly, a
 * header omitted, a field read from the wrong place — none of which any amount
 * of parsing-only testing would have found.
 *
 * <p>Deliberately not a mocking framework. The point is that bytes go over a
 * socket and come back, because that is the part nobody had ever run.
 */
final class FakeGateway implements AutoCloseable {

    /** What a caller actually sent us, so a test can assert on the request. */
    record Call(String method, String path, Map<String, String> headers, String body) {

        boolean bodyContains(String needle) {
            return body != null && body.contains(needle);
        }

        String header(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
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

    FakeGateway() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake gateway", e);
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

            Reply reply = replies.get(exchange.getRequestMethod() + " " + path);
            if (reply == null) {
                reply = replies.get(path);
            }
            if (reply == null) {
                // A route nobody registered means the provider called somewhere
                // unexpected. 418 rather than 404 so it cannot be mistaken for a
                // provider's own "not found".
                reply = new Reply(418, "{\"message\":\"fake gateway has no route for " + path + "\"}");
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    /** Answer this path with this JSON. Method-qualified ("POST /x") if it matters. */
    FakeGateway on(String pathOrMethodAndPath, String json) {
        return on(pathOrMethodAndPath, 200, json);
    }

    FakeGateway on(String pathOrMethodAndPath, int status, String json) {
        replies.put(pathOrMethodAndPath, new Reply(status, json));
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

    /** The one call to a path, failing loudly if it was never made. */
    Call call(String path) {
        return calls().stream()
                .filter(c -> c.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing was sent to " + path + "; calls were: "
                                + calls().stream().map(c -> c.method() + " " + c.path()).toList()));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
