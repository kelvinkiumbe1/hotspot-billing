package com.spalimited.hotspotbilling.service.olt;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Typing at an OLT and reading what scrolls back.
 *
 * <p>Telnet over a raw socket, because that is what these boxes offer and there
 * is no SSH client available to this build. That means credentials cross the
 * management network in the clear, which is a real thing to know rather than a
 * detail: an OLT should be on an isolated management VLAN, and if it is not then
 * this is one more reason to put it there. The admin says so where the password
 * is entered.
 *
 * <p>Reading a CLI is mostly waiting. There is no framing, no content length and
 * no end-of-response marker — only the prompt coming back, which is why
 * {@link OltDialect.Dialect#prompts()} is the most important field in the
 * dialect. A missing prompt does not fail loudly; it hangs until the read timeout,
 * once per command.
 */
@Slf4j
public class OltCli implements AutoCloseable {

    /**
     * How long to wait for a prompt.
     *
     * <p>Generous, because an OLT listing a full PON port genuinely takes seconds
     * and cutting it off mid-table produces a half-read answer that looks like a
     * short one. Bounded, because the alternative to a timeout here is a thread
     * held until the process restarts.
     */
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final int CONNECT_TIMEOUT_MS = 8_000;

    /** Stops a runaway device filling the heap one byte at a time. */
    private static final int MAX_RESPONSE = 512 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final OltDialect.Dialect dialect;
    private final List<String> transcript = new ArrayList<>();

    public OltCli(String host, int port, OltDialect.Dialect dialect) throws IOException {
        this.dialect = dialect;
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(host, port <= 0 ? 23 : port),
                CONNECT_TIMEOUT_MS);
        this.socket.setSoTimeout(READ_TIMEOUT_MS);
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    /** Everything sent and received, for the audit trail and for the operator. */
    public List<String> transcript() {
        return List.copyOf(transcript);
    }

    /**
     * Waits for something, then sends a line.
     *
     * <p>The wait first is not optional. An OLT that has not finished printing its
     * banner discards what arrives during it, so sending a username too early is
     * a login that silently never happens.
     */
    public String send(String line) throws IOException {
        transcript.add("> " + line);
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String response = readUntilPrompt();
        transcript.add(response);
        return response;
    }

    /**
     * Reads until a prompt, answering the pager along the way.
     *
     * <p>Every one of these CLIs pages long output and waits for a keypress. Not
     * answering it means the read times out holding half a table, which reads as
     * "this PON port has four ONUs on it" when it has forty.
     */
    public String readUntilPrompt() throws IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[4096];
        while (true) {
            int read;
            try {
                read = in.read(chunk);
            } catch (java.net.SocketTimeoutException e) {
                // What we have is what there is. Returned rather than thrown
                // because a partial answer with the prompt missing is still worth
                // showing an operator -- it is usually the error message.
                log.debug("OLT read timed out with {} bytes buffered", buffer.length());
                return buffer.toString();
            }
            if (read < 0) {
                return buffer.toString();
            }
            buffer.append(new String(chunk, 0, read, StandardCharsets.US_ASCII));
            if (buffer.length() > MAX_RESPONSE) {
                log.warn("OLT sent more than {} bytes without a prompt; giving up", MAX_RESPONSE);
                return buffer.toString();
            }

            String tail = tailOf(buffer);
            if (dialect.moreMarker() != null && tail.contains(dialect.moreMarker())) {
                // A space is the next page on every one of these. Enter is the
                // next line on some and quits on others, which is why it is a
                // space.
                out.write(' ');
                out.flush();
                continue;
            }
            for (String prompt : dialect.prompts()) {
                if (tail.stripTrailing().endsWith(prompt)) {
                    return buffer.toString();
                }
            }
        }
    }

    /** Only the last stretch matters for prompt matching, and it keeps this cheap. */
    private static String tailOf(StringBuilder buffer) {
        int from = Math.max(0, buffer.length() - 200);
        return buffer.substring(from);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that has already gone is not news.
        }
    }
}
