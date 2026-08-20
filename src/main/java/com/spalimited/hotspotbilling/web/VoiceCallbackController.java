package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.calls.CallCentreService;
import com.spalimited.hotspotbilling.service.calls.VoiceXml;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * What the voice provider calls when something happens to a call.
 *
 * <p>Public by necessity: this is called by somebody else's server, so there is
 * no session and no key to check. The secret is in the URL instead -- the
 * operator pastes the whole thing, token included, into their provider
 * dashboard, and a request with the wrong token gets a rejection rather than an
 * explanation.
 *
 * <p>The reply to a single POST is the entire instruction for what happens to a
 * live call. There is no retry and no error channel: get it wrong and a customer
 * hears silence and hangs up, with nothing anywhere to say why. So this class
 * does as little thinking as possible and hands off immediately.
 */
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
@Slf4j
public class VoiceCallbackController {

    private final CallCentreService callCentre;

    /**
     * One callback, whatever it is about.
     *
     * <p>The provider posts form-encoded and sends every field it has, which
     * differs between the ringing, answered and completed stages of the same
     * call. Taking the whole map and reading what is present beats declaring a
     * record with twenty nullable fields that has to change whenever they add
     * one.
     */
    @PostMapping(value = "/{token}", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> callback(@PathVariable String token,
                                           @RequestParam Map<String, String> params) {
        if (!callCentre.tokenMatches(token)) {
            // Deliberately terse. Anybody hitting this without the token is
            // scanning, and telling them what is wrong helps only them.
            log.warn("Voice callback with a bad token from an unknown caller");
            return ResponseEntity.status(403).body(VoiceXml.reject());
        }

        String sessionId = params.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("Voice callback with no session id: {}", params.keySet());
            return xml(VoiceXml.reject());
        }

        CallCentreService.Callback cb = new CallCentreService.Callback(
                sessionId,
                params.get("direction"),
                params.get("callerNumber"),
                params.get("destinationNumber"),
                params.get("callSessionState"),
                params.get("hangupCause"),
                params.get("recordingUrl"),
                integer(params.get("durationInSeconds")),
                decimal(params.get("amount")),
                params.get("currencyCode"),
                // isActive is "1" while the call is live and "0" on the final
                // callback. It is the field that decides whether we are being
                // asked what to do or told what happened.
                "1".equals(params.get("isActive")));

        if (!cb.active()) {
            callCentre.finish(cb);
            // Nothing to instruct: the call is over. An empty body is what the
            // provider expects here, and returning XML with actions in it would
            // be an instruction about a call that no longer exists.
            return ResponseEntity.ok().build();
        }

        // Outbound means we placed it -- the leg that just answered is the agent,
        // and what happens next is bridging them to the customer. Inbound is a
        // customer ringing us.
        boolean outbound = cb.direction() != null
                && cb.direction().toLowerCase().startsWith("out");
        try {
            return xml(outbound ? callCentre.answerOutbound(cb) : callCentre.answerInbound(cb));
        } catch (Exception e) {
            // A caller is on the line right now. Anything is better than a stack
            // trace turning into silence.
            log.error("Voice callback for session {} failed: {}", sessionId, e.getMessage(), e);
            return xml(VoiceXml.sayAndHangUp(
                    "Sorry, we cannot take your call right now. Please try again shortly."));
        }
    }

    private static ResponseEntity<String> xml(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    private static Integer integer(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : Integer.valueOf(raw.strip());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * The provider sends cost as "KES 1.2000" -- a currency and an amount in one
     * field -- so the digits are picked out rather than parsed whole.
     */
    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        try {
            return digits.isBlank() || ".".equals(digits) ? null : new BigDecimal(digits);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
