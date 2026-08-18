package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.config.MpesaProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Where this server can be reached from the outside.
 *
 * <p>Derived from the M-Pesa callback URL, because that is the one address an
 * operator has already had to get right — Safaricom will not deliver to a
 * hostname that does not resolve, so a working deployment has a working value
 * here. Guessing from the inbound request would be worse: behind a reverse proxy
 * or a captive-portal redirect it produces a LAN address a payment provider
 * cannot reach, and the failure is a payment that silently never completes.
 *
 * <p>Orange Money and Wave both need to be told where to send the customer back
 * to and where to notify, and Orange refuses a payment outright without them.
 * Returning null rather than a plausible-looking guess lets those providers say
 * "set your callback URL first" instead of starting a payment that cannot end.
 */
@Component
@RequiredArgsConstructor
public class PublicUrls {

    private final MpesaProperties mpesa;

    /**
     * The scheme and host, with no trailing slash, or null when it was never
     * configured.
     *
     * <p>The sample value shipped in application.properties points at
     * example.com, which resolves and does nothing — exactly the case that
     * produces a payment stuck pending forever, so it is treated as unset.
     */
    public String origin() {
        String callback = mpesa.callbackUrl();
        if (callback == null || callback.isBlank() || callback.contains("example.com")) {
            return null;
        }
        int api = callback.indexOf("/api/");
        String base = api > 0 ? callback.substring(0, api) : callback;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isBlank() ? null : base;
    }
}
