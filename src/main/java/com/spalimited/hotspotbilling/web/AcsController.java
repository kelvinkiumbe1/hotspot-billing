package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.acs.AcsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the routers call in.
 *
 * <p>The only endpoint in this application whose clients are cheap consumer
 * hardware in other people's houses, which shapes everything about it.
 *
 * <p>It is deliberately outside {@code /api}: a CPE is configured with an ACS URL
 * once, at the factory or by a provisioning script, and that URL then lives in
 * thousands of devices that may never be reachable again. {@code /acs} is short,
 * conventional, and will not move.
 *
 * <p>It answers 204 rather than an error whenever it has nothing to say, because
 * a CPE that receives an error page does not report it anywhere a human will see:
 * it retries, fails again, and the device is simply absent from the ACS with
 * nobody knowing why.
 */
@RestController
@RequestMapping("/acs")
@RequiredArgsConstructor
@Slf4j
public class AcsController {

    /**
     * The cookie CWMP sessions are held together with.
     *
     * <p>A session is several HTTP requests and the protocol has no other way to
     * tie them together. Every CPE implements cookies for exactly this reason,
     * and one that does not gets a fresh session each request -- which still works
     * for an Inform, just not for anything that needs a follow-up.
     */
    private static final String SESSION_COOKIE = "acs-session";

    private final AcsService acs;

    @PostMapping
    public ResponseEntity<String> handle(
            @RequestBody(required = false) byte[] body,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionId,
            HttpServletRequest request) {

        AcsService.Reply reply = acs.handle(body, sessionId, clientAddress(request));

        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.status());
        if (reply.sessionId() != null && !reply.sessionId().equals(sessionId)) {
            // HttpOnly, and Path scoped to this endpoint. Nothing in a browser
            // should ever see this, and a CPE does not care either way.
            response.header(HttpHeaders.SET_COOKIE,
                    SESSION_COOKIE + "=" + reply.sessionId() + "; Path=/acs; HttpOnly");
        }
        if (reply.body() == null) {
            // 204, which is how a CWMP session ends. The device stops posting.
            return response.build();
        }
        return response.contentType(MediaType.TEXT_XML).body(reply.body());
    }

    /**
     * Where the device really is.
     *
     * <p>An ACS almost always sits behind a proxy, and the remote address there is
     * the proxy's. This is only ever used for the log and for showing an operator
     * roughly where a box is, so a forwarded header is trusted -- it is not
     * deciding anything.
     */
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
