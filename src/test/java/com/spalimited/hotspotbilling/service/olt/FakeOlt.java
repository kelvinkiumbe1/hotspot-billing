package com.spalimited.hotspotbilling.service.olt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An OLT that isn't one.
 *
 * <p>The same idea as FakeGateway on the payments side, for the same reason: none
 * of this conversation had ever been run. There is no OLT sandbox anywhere and no
 * way to get one, so a scripted telnet server on a real socket is the closest
 * this can come — it proves the commands are built and sequenced correctly and
 * that the answers are read the way a CLI actually delivers them.
 *
 * <p>It deliberately behaves like the awkward parts of a real box: it prints a
 * banner before anything, it pages long output and waits for a keypress, and it
 * answers an unknown command with prose rather than an error code.
 */
final class FakeOlt implements AutoCloseable {

    private final ServerSocket server;
    private final Thread thread;
    private final Map<String, String> replies = new LinkedHashMap<>();
    private final List<String> received = new CopyOnWriteArrayList<>();

    private volatile String prompt = "OLT#";
    private volatile String banner = "\r\nHuawei Integrated Access Software\r\n";
    private volatile String pageAfter;

    FakeOlt() {
        try {
            server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException("could not start the fake OLT", e);
        }
        thread = new Thread(this::serve, "fake-olt");
        thread.setDaemon(true);
        thread.start();
    }

    /** Answer this command with this text. */
    FakeOlt on(String command, String reply) {
        replies.put(command, reply);
        return this;
    }

    /** Break the reply to this command across a pager, as a real OLT does. */
    FakeOlt pages(String command) {
        pageAfter = command;
        return this;
    }

    FakeOlt prompt(String value) {
        prompt = value;
        return this;
    }

    int port() {
        return server.getLocalPort();
    }

    /** Every line the client sent, so a test can assert on the sequence. */
    List<String> received() {
        return new ArrayList<>(received);
    }

    private void serve() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept();
                 InputStream in = socket.getInputStream();
                 OutputStream out = socket.getOutputStream()) {

                // A banner first, then the prompt. A client that sends before
                // reading this loses what it sent, which is a real failure mode.
                write(out, banner + prompt);

                StringBuilder line = new StringBuilder();
                int b;
                while ((b = in.read()) >= 0) {
                    char c = (char) b;
                    if (c == ' ' && line.isEmpty()) {
                        // The pager's "next page" keypress.
                        write(out, "\r\n(rest of the output)\r\n" + prompt);
                        continue;
                    }
                    if (c == '\r') {
                        continue;
                    }
                    if (c != '\n') {
                        line.append(c);
                        continue;
                    }
                    String command = line.toString().trim();
                    line.setLength(0);
                    received.add(command);

                    String reply = replies.get(command);
                    if (reply == null) {
                        // Prose, not an error code. Which is exactly why
                        // OltProvisioningService has to read prose.
                        write(out, "\r\n% Unknown command\r\n" + prompt);
                        continue;
                    }
                    if (command.equals(pageAfter)) {
                        write(out, "\r\n" + reply + "\r\n---- More ( 50% )----");
                        continue;
                    }
                    write(out, "\r\n" + reply + "\r\n" + prompt);
                }
            } catch (IOException e) {
                // The client hung up, or we are closing. Neither is news.
                if (server.isClosed()) {
                    return;
                }
            }
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        thread.interrupt();
    }
}
