package com.spalimited.hotspotbilling.service.radius;

import com.spalimited.hotspotbilling.domain.RadiusClient;
import com.spalimited.hotspotbilling.domain.RadiusSession;
import com.spalimited.hotspotbilling.repository.RadiusClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one request into one reply.
 *
 * <p>Kept apart from the socket handling so the decisions can be tested
 * without opening a port, and from the auth logic so that logic never has to
 * know what a packet looks like.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RadiusRequestHandler {

    private final RadiusAuthService auth;
    private final RadiusAccountingService accounting;
    private final RadiusSettingsService settings;
    private final RadiusClientRepository clients;

    /**
     * Answers an Access-Request.
     *
     * <p>Note what is <em>not</em> checked: there is no way to verify that an
     * Access-Request came from something holding the shared secret. The format
     * simply has no MAC on it unless the NAS chooses to add one. A wrong secret
     * therefore surfaces as every login failing, and the operator has to be
     * told that in those words rather than left hunting for a password problem.
     */
    public byte[] handleAuth(RadiusPacket request, RadiusClient client, String sourceAddress) {
        if (request.code() != RadiusPacket.ACCESS_REQUEST) {
            return null;
        }
        String username = request.string(RadiusPacket.USER_NAME);
        String secret = client.getSharedSecret();

        RadiusAuthService.PasswordCheck check = passwordCheck(request, secret);
        RadiusAuthService.Decision decision = auth.authorise(
                username, check, client, settings.get().getInterimSeconds());

        touch(client, decision.accept());

        List<RadiusPacket.Attribute> attributes = new ArrayList<>(decision.attributes());
        if (!decision.accept()) {
            attributes.add(RadiusPacket.text(RadiusPacket.REPLY_MESSAGE, decision.message()));
        } else {
            // Class comes back untouched in every accounting packet for this
            // session, so it is how a Stop is tied to the pass that paid for it
            // even after a restart of this service.
            attributes.add(RadiusPacket.text(RadiusPacket.CLASS, classFor(decision)));
        }

        log.debug("RADIUS {} for '{}' from {}: {}",
                decision.accept() ? "accept" : "reject", username, sourceAddress, decision.message());

        RadiusPacket response = new RadiusPacket(
                decision.accept() ? RadiusPacket.ACCESS_ACCEPT : RadiusPacket.ACCESS_REJECT,
                request.identifier(), request.authenticator(), attributes);
        return response.encodeResponse(request.authenticator(), secret);
    }

    /**
     * Answers an Accounting-Request.
     *
     * <p>Unlike Access-Request, this one carries a real MAC, and it is checked.
     * Without that check anyone able to reach the port could write usage
     * history for any customer — draining a stranger's pass, or clearing their
     * own. The reply is sent only after the record is safely stored, because a
     * NAS that gets an acknowledgement stops retransmitting.
     */
    public byte[] handleAccounting(RadiusPacket request, RadiusClient client, String sourceAddress,
                                   byte[] raw) {
        if (request.code() != RadiusPacket.ACCOUNTING_REQUEST) {
            return null;
        }
        String secret = client.getSharedSecret();
        if (!request.accountingAuthenticatorValid(raw, raw.length, secret)) {
            log.warn("Accounting packet from {} failed its integrity check — "
                    + "the shared secret there does not match ours", sourceAddress);
            return null;
        }

        Integer statusType = request.integer(RadiusPacket.ACCT_STATUS_TYPE);
        String sessionId = request.string(RadiusPacket.ACCT_SESSION_ID);
        String username = request.string(RadiusPacket.USER_NAME);
        if (statusType == null || sessionId == null || sessionId.isBlank()) {
            // Acknowledged anyway: retransmitting a packet we will never
            // understand only wastes the router's time.
            return acknowledge(request, secret);
        }

        RadiusSession.Kind kind = RadiusSession.Kind.HOTSPOT;
        Long voucherId = null;
        Long subscriberId = null;
        String marker = request.string(RadiusPacket.CLASS);
        if (marker != null) {
            if (marker.startsWith("v:")) {
                voucherId = parseId(marker.substring(2));
            } else if (marker.startsWith("s:")) {
                subscriberId = parseId(marker.substring(2));
                kind = RadiusSession.Kind.PPPOE;
            }
        }
        if (voucherId == null && subscriberId == null) {
            // No Class to go on: either the NAS does not echo it, or this
            // session predates our last restart. The username is the next best
            // answer, and discarding the usage instead would surface much later
            // as a pass that never seems to run down.
            var owner = auth.ownerOf(username);
            voucherId = owner.voucherId();
            subscriberId = owner.subscriberId();
            if (owner.kind() != null) {
                kind = owner.kind();
            }
        }

        var report = new RadiusAccountingService.Report(
                sourceAddress,
                sessionId,
                username == null ? "" : username,
                statusType,
                request.octets(RadiusPacket.ACCT_INPUT_OCTETS, RadiusPacket.ACCT_INPUT_GIGAWORDS),
                request.octets(RadiusPacket.ACCT_OUTPUT_OCTETS, RadiusPacket.ACCT_OUTPUT_GIGAWORDS),
                orZero(request.integer(RadiusPacket.ACCT_SESSION_TIME)),
                request.address(RadiusPacket.FRAMED_IP_ADDRESS),
                request.string(RadiusPacket.CALLING_STATION_ID),
                request.string(RadiusPacket.CALLED_STATION_ID),
                request.string(RadiusPacket.NAS_PORT_ID),
                terminateCause(request.integer(RadiusPacket.ACCT_TERMINATE_CAUSE)),
                client.getRouterId());

        accounting.record(report, kind, voucherId, subscriberId);
        touch(client, true);
        return acknowledge(request, secret);
    }

    private byte[] acknowledge(RadiusPacket request, String secret) {
        return new RadiusPacket(RadiusPacket.ACCOUNTING_RESPONSE, request.identifier(),
                request.authenticator(), List.of())
                .encodeResponse(request.authenticator(), secret);
    }

    /**
     * How the password in this packet gets compared, without the auth service
     * ever seeing the packet.
     */
    private RadiusAuthService.PasswordCheck passwordCheck(RadiusPacket request, String secret) {
        RadiusCredentials.Kind kind = RadiusCredentials.kindOf(request);
        return new RadiusAuthService.PasswordCheck() {
            @Override
            public boolean matches(String knownPassword) {
                if (knownPassword == null) {
                    return false;
                }
                if (kind == RadiusCredentials.Kind.PAP) {
                    String offered = RadiusCredentials.decodePap(
                            request.raw(RadiusPacket.USER_PASSWORD).orElse(new byte[0]),
                            request.authenticator(), secret);
                    return offered != null && java.security.MessageDigest.isEqual(
                            offered.getBytes(StandardCharsets.UTF_8),
                            knownPassword.getBytes(StandardCharsets.UTF_8));
                }
                if (kind == RadiusCredentials.Kind.CHAP) {
                    byte[] challenge = request.raw(RadiusPacket.CHAP_CHALLENGE)
                            .orElse(request.authenticator());
                    return RadiusCredentials.chapMatches(
                            request.raw(RadiusPacket.CHAP_PASSWORD).orElse(null),
                            challenge, knownPassword);
                }
                return false;
            }

            @Override
            public boolean unsupported() {
                return kind == RadiusCredentials.Kind.UNSUPPORTED;
            }
        };
    }

    private static String classFor(RadiusAuthService.Decision decision) {
        return decision.voucherId() != null ? "v:" + decision.voucherId() : "s:" + decision.subscriberId();
    }

    private static Long parseId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long orZero(Integer value) {
        return value == null ? 0 : Integer.toUnsignedLong(value);
    }

    /** RFC 2866 §5.10, in words rather than numbers nobody remembers. */
    static String terminateCause(Integer code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case 1 -> "User logged out";
            case 2 -> "Lost carrier";
            case 4 -> "Idle timeout";
            case 5 -> "Session timeout";
            case 6 -> "Admin reset";
            case 7 -> "Admin reboot";
            case 11 -> "NAS request";
            case 12 -> "NAS reboot";
            case 15 -> "Service unavailable";
            case 17 -> "User error";
            default -> "Cause " + code;
        };
    }

    /** Keeps the per-NAS counters honest so the admin screen means something. */
    private void touch(RadiusClient client, boolean accepted) {
        try {
            client.setLastRequestAt(java.time.Instant.now());
            if (accepted) {
                client.setAccepts(client.getAccepts() + 1);
            } else {
                client.setRejects(client.getRejects() + 1);
            }
            clients.save(client);
        } catch (Exception e) {
            // Statistics are not worth failing a login over.
            log.debug("Could not update RADIUS client counters: {}", e.getMessage());
        }
    }
}
