package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.RadiusClient;
import com.spalimited.hotspotbilling.domain.RadiusSession;
import com.spalimited.hotspotbilling.repository.RadiusClientRepository;
import com.spalimited.hotspotbilling.repository.RadiusSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ends a session that is still running (RFC 5176).
 *
 * <p>Session-Timeout tells the NAS when to stop, but only when the pass is
 * bought — it cannot cover the customer whose plan changes, whose payment is
 * reversed, or who hits a fair-use cap mid-stream. For those, the session has
 * to be cut while it is live, and this is the only way to do it on hardware we
 * do not otherwise control.
 *
 * <p>Best-effort by nature. A NAS may have CoA disabled, may listen on a
 * different port, or may simply be unreachable — so a failure here is reported
 * honestly rather than swallowed, because "we cut them off" and "we asked
 * politely and heard nothing" are very different things to tell an operator.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RadiusDisconnectService {

    private static final int TIMEOUT_MS = 3_000;

    private final RadiusSessionRepository sessions;
    private final RadiusClientRepository clients;
    private final RadiusSettingsService settings;

    private final AtomicInteger identifiers = new AtomicInteger();

    /** What happened when we asked a NAS to drop somebody. */
    public record Result(int asked, int acknowledged, List<String> problems) {

        public boolean allAcknowledged() {
            return asked > 0 && acknowledged == asked;
        }
    }

    /**
     * Drops every live session for a username.
     *
     * <p>Every one, not the first: the same code can be online at two sites,
     * and cutting one of them off while leaving the other is worse than doing
     * nothing, because it looks like it worked.
     */
    @Transactional
    public Result disconnect(String username) {
        if (!settings.get().isDisconnectEnabled()) {
            return new Result(0, 0, List.of("Cutting sessions off is switched off in RADIUS settings"));
        }
        List<RadiusSession> live = sessions.findByUsernameAndStoppedAtIsNull(username);
        if (live.isEmpty()) {
            return new Result(0, 0, List.of());
        }

        int acknowledged = 0;
        List<String> problems = new ArrayList<>();
        for (RadiusSession session : live) {
            RadiusClient client = clients.findAll().stream()
                    .filter(c -> RadiusServer.matches(c.getAddress(), session.getNasAddress()))
                    .findFirst().orElse(null);
            if (client == null) {
                problems.add("No RADIUS client configured for " + session.getNasAddress());
                continue;
            }
            try {
                if (send(client, session)) {
                    acknowledged++;
                    session.setStoppedAt(java.time.Instant.now());
                    session.setTerminateCause("Cut off by the operator");
                    sessions.save(session);
                } else {
                    problems.add(session.getNasAddress() + " refused the request");
                }
            } catch (Exception e) {
                problems.add(session.getNasAddress() + ": " + e.getMessage());
            }
        }
        return new Result(live.size(), acknowledged, problems);
    }

    /** Convenience for the pass that just ran out. */
    @Transactional
    public Result disconnectVoucher(Long voucherId, String code) {
        return sessions.findByVoucherIdAndStoppedAtIsNull(voucherId).isEmpty()
                ? new Result(0, 0, List.of())
                : disconnect(code);
    }

    /**
     * Sends one Disconnect-Request and waits for the answer.
     *
     * <p>The session is identified by both its Acct-Session-Id and the
     * username. Some vendors match on one, some on the other, and sending both
     * is the difference between this working everywhere and working on the
     * hardware it was written against.
     */
    private boolean send(RadiusClient client, RadiusSession session) throws Exception {
        List<RadiusPacket.Attribute> attributes = List.of(
                RadiusPacket.text(RadiusPacket.USER_NAME, session.getUsername()),
                RadiusPacket.text(RadiusPacket.ACCT_SESSION_ID, session.getAcctSessionId()));

        RadiusPacket request = new RadiusPacket(RadiusPacket.DISCONNECT_REQUEST,
                identifiers.incrementAndGet() & 0xFF, new byte[16], attributes);
        byte[] datagram = request.encodeRequest(client.getSharedSecret());

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            InetAddress target = InetAddress.getByName(hostOf(client.getAddress(), session.getNasAddress()));
            socket.send(new DatagramPacket(datagram, datagram.length, target, client.getCoaPort()));

            byte[] buffer = new byte[4096];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            RadiusPacket response = RadiusPacket.decode(reply.getData(), reply.getLength());
            if (response.code() == RadiusPacket.DISCONNECT_NAK) {
                log.info("{} declined to disconnect {}: {}", client.getName(),
                        session.getUsername(), response.string(RadiusPacket.REPLY_MESSAGE));
            }
            return response.code() == RadiusPacket.DISCONNECT_ACK;
        } catch (java.net.SocketTimeoutException e) {
            throw new IllegalStateException("no answer on port " + client.getCoaPort()
                    + " — CoA may be switched off on that device");
        }
    }

    /**
     * Where to send it. A client configured as a CIDR block has no single
     * address to talk to, so the address the session actually came from is
     * used instead.
     */
    private static String hostOf(String configured, String sessionSource) {
        return configured != null && configured.contains("/") ? sessionSource : configured;
    }
}
