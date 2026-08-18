package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.RadiusClient;
import com.spalimited.hotspotbilling.repository.RadiusClientRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answers logins for anybody's hardware.
 *
 * <p>Two UDP sockets — one for authentication, one for accounting — and a
 * virtual thread per packet. UDP has no connections to manage and no
 * backpressure to respect: a datagram arrives, is answered, and is forgotten.
 * What it does have is retransmission, so the same request will arrive again if
 * the reply is lost, and everything downstream is written to expect that.
 *
 * <p>Off unless an operator switches it on. Opening two well-known ports on a
 * deployment that never asked for RADIUS is not a default anyone should get.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RadiusServer {

    private static final int MAX_PACKET = 4096;

    private final RadiusClientRepository clients;
    private final RadiusSettingsService settings;
    private final RadiusRequestHandler handler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<DatagramSocket> sockets = new ArrayList<>();
    private ExecutorService workers;
    private ExecutorService listeners;

    /**
     * Replies already sent, so a retransmission gets the same answer.
     *
     * <p>Without this, a NAS whose reply was lost re-asks and gets a fresh
     * decision — which for a pass with seconds left on it can be an accept the
     * first time and a reject the second, from the customer's point of view at
     * random.
     */
    private final Map<String, byte[]> recentReplies = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void startIfEnabled() {
        if (settings.get().isEnabled()) {
            start();
        }
    }

    /** Idempotent: called on boot, and again when an operator flips the switch. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        var config = settings.get();
        try {
            listeners = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "radius-listener");
                t.setDaemon(true);
                return t;
            });
            workers = Executors.newVirtualThreadPerTaskExecutor();

            listen(config.getAuthPort(), true);
            listen(config.getAcctPort(), false);
            log.info("RADIUS listening on {} (auth) and {} (accounting)",
                    config.getAuthPort(), config.getAcctPort());
        } catch (Exception e) {
            running.set(false);
            closeSockets();
            log.error("RADIUS could not start: {}", e.getMessage());
            throw new IllegalStateException("Could not open the RADIUS ports: " + e.getMessage()
                    + ". Ports below 1024 need privileges, and 1812/1813 may already be in use.", e);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeSockets();
        if (listeners != null) {
            listeners.shutdownNow();
        }
        if (workers != null) {
            workers.shutdown();
        }
        recentReplies.clear();
        log.info("RADIUS stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Applies a settings change by restarting on the new ports. */
    public synchronized void restart() {
        stop();
        if (settings.get().isEnabled()) {
            start();
        }
    }

    private void listen(int port, boolean auth) throws Exception {
        DatagramSocket socket = new DatagramSocket(port);
        sockets.add(socket);
        listeners.submit(() -> {
            byte[] buffer = new byte[MAX_PACKET];
            while (running.get() && !socket.isClosed()) {
                try {
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagram);
                    // Copied before handing off: the buffer is reused by the
                    // next receive, and a worker still reading it would see
                    // another NAS's packet halfway through its own.
                    byte[] copy = new byte[datagram.getLength()];
                    System.arraycopy(datagram.getData(), datagram.getOffset(), copy, 0, datagram.getLength());
                    InetAddress source = datagram.getAddress();
                    int sourcePort = datagram.getPort();
                    workers.submit(() -> handle(socket, copy, source, sourcePort, auth));
                } catch (Exception e) {
                    if (running.get() && !socket.isClosed()) {
                        log.debug("RADIUS receive on {}: {}", port, e.getMessage());
                    }
                }
            }
        });
    }

    private void handle(DatagramSocket socket, byte[] data, InetAddress source, int sourcePort,
                        boolean auth) {
        String address = source.getHostAddress();
        RadiusClient client = findClient(address);
        if (client == null) {
            // Silence, not a rejection. A RADIUS server that answers strangers
            // is an oracle for probing which usernames exist, and a reject is
            // itself the confirmation that something is listening here.
            log.debug("RADIUS packet from unknown source {} ignored", address);
            return;
        }

        RadiusPacket request;
        try {
            request = RadiusPacket.decode(data, data.length);
        } catch (IllegalArgumentException e) {
            log.debug("Malformed RADIUS packet from {}: {}", address, e.getMessage());
            return;
        }

        String key = address + "/" + request.code() + "/" + request.identifier() + "/"
                + java.util.HexFormat.of().formatHex(request.authenticator());
        byte[] cached = recentReplies.get(key);
        if (cached != null) {
            send(socket, cached, source, sourcePort);
            return;
        }

        byte[] reply;
        try {
            reply = auth
                    ? handler.handleAuth(request, client, address)
                    : handler.handleAccounting(request, client, address, data);
        } catch (Exception e) {
            // No reply at all. A NAS treats silence as "try again", which is
            // the right outcome for a fault on our side — an Access-Reject
            // would tell a paying customer their password is wrong.
            log.warn("RADIUS request from {} failed: {}", address, e.toString());
            return;
        }
        if (reply == null) {
            return;
        }

        // Bounded, because a busy hotspot would otherwise fill this map with
        // one entry per login until the process runs out of memory.
        if (recentReplies.size() > 5_000) {
            recentReplies.clear();
        }
        recentReplies.put(key, reply);
        send(socket, reply, source, sourcePort);
    }

    private void send(DatagramSocket socket, byte[] reply, InetAddress source, int port) {
        try {
            socket.send(new DatagramPacket(reply, reply.length, source, port));
        } catch (Exception e) {
            log.debug("Could not reply to {}: {}", source.getHostAddress(), e.getMessage());
        }
    }

    /**
     * Finds the configured client for a source address, matching a plain
     * address exactly and a CIDR block by prefix.
     */
    private RadiusClient findClient(String address) {
        for (RadiusClient client : clients.findByEnabledTrue()) {
            if (matches(client.getAddress(), address)) {
                return client;
            }
        }
        return null;
    }

    static boolean matches(String configured, String source) {
        if (configured == null || source == null) {
            return false;
        }
        configured = configured.trim();
        if (!configured.contains("/")) {
            return configured.equals(source);
        }
        try {
            String[] parts = configured.split("/");
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            long network = ipv4(parts[0]);
            long candidate = ipv4(source);
            if (network < 0 || candidate < 0) {
                return false;
            }
            // A /0 would shift by 32, which in Java is a shift by 0 — every
            // address would match nothing instead of everything.
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (network & mask) == (candidate & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static long ipv4(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            return -1;
        }
        long value = 0;
        for (String octet : octets) {
            int part = Integer.parseInt(octet.trim());
            if (part < 0 || part > 255) {
                return -1;
            }
            value = (value << 8) | part;
        }
        return value;
    }

    private void closeSockets() {
        for (DatagramSocket socket : sockets) {
            try {
                socket.close();
            } catch (Exception ignore) {
                // closing on the way down; nothing useful to do
            }
        }
        sockets.clear();
    }

    /** Exposed for the admin screen: what the server thinks it is doing. */
    public Map<String, Object> status() {
        var config = settings.get();
        return Map.of(
                "enabled", config.isEnabled(),
                "running", running.get(),
                "authPort", config.getAuthPort(),
                "acctPort", config.getAcctPort());
    }
}
