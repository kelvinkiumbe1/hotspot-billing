package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.service.UssdService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * USSD gateway callback. Register this URL with the aggregator (Africa's
 * Talking and the rest post the same four fields) as the handler for the
 * operator's short code:
 *
 * <pre>{@code POST {PUBLIC_URL}/api/ussd}</pre>
 *
 * <p>The reply is plain text: {@code CON …} keeps the session open for the
 * next keypress, {@code END …} closes it with a final message.
 */
@RestController
@RequiredArgsConstructor
public class UssdController {

    private final UssdService ussd;

    /** Form-encoded is what the aggregators actually send. */
    @PostMapping(value = "/api/ussd",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String handleForm(@RequestParam(required = false) String phoneNumber,
                             @RequestParam(required = false) String text) {
        return ussd.handle(phoneNumber, text);
    }

    /** JSON, for aggregators that post it and for testing by hand. */
    @PostMapping(value = "/api/ussd",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_PLAIN_VALUE)
    public String handleJson(@RequestBody Map<String, String> body) {
        return ussd.handle(body.get("phoneNumber"), body.get("text"));
    }

    /**
     * Walks the menu without a gateway, so an operator can see exactly what a
     * customer would get before pointing a real short code at this.
     */
    @PostMapping("/api/admin/ussd/simulate")
    @PreAuthorize("hasAuthority('SETTINGS')")
    public Map<String, String> simulate(@RequestBody Map<String, String> body) {
        return Map.of("reply", ussd.handle(body.get("phone"), body.get("text")));
    }
}
