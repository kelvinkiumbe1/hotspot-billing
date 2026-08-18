package com.spalimited.hotspotbilling.service.payments;

import com.spalimited.hotspotbilling.domain.PaymentMandate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Building an M-Pesa Ratiba standing order request.
 *
 * <p>Kept apart from the HTTP call so the two things most likely to be wrong
 * can be tested: Safaricom's date format and its frequency codes. Both are
 * bare strings on the wire with no validation worth the name — a date in the
 * wrong format or a frequency off by one is accepted, and the customer is then
 * debited weekly for a monthly service, or never at all.
 */
public final class Ratiba {

    private Ratiba() {
    }

    /** Safaricom wants yyyyMMdd, with no separators. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * Safaricom's frequency codes.
     *
     * <p>Written out rather than derived from an ordinal, because an enum
     * reordered later would silently change every customer's billing period.
     */
    static String frequencyCode(PaymentMandate.Frequency frequency) {
        return switch (frequency) {
            case WEEKLY -> "3";
            case MONTHLY -> "4";
            case QUARTERLY -> "6";
            case YEARLY -> "8";
        };
    }

    static String date(LocalDate day) {
        return day.format(DATE);
    }

    /**
     * A name unique to this mandate.
     *
     * <p>Safaricom gives back no id of its own, so the name we send is the only
     * handle there will ever be — it has to be unique per mandate and it has to
     * be reproducible from what we store.
     */
    static String standingOrderName(Long subscriberId, LocalDate startsOn) {
        return "SUB" + subscriberId + "-" + date(startsOn);
    }

    /**
     * The request body.
     *
     * @param shortCode the paybill or till the money lands in
     * @param till      true for a Buy Goods till, false for a paybill — this
     *                  changes both the transaction type and the identifier
     *                  type, and getting one right while the other is wrong is
     *                  rejected with a message that does not say which
     */
    public static Map<String, Object> request(PaymentMandate mandate, String phoneNumber,
                                              String shortCode, boolean till,
                                              String accountReference, String callbackUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("StandingOrderName",
                standingOrderName(mandate.getSubscriberId(), mandate.getStartsOn()));
        body.put("StartDate", date(mandate.getStartsOn()));
        // Safaricom requires an end date. A mandate the operator wants open
        // ended gets ten years, which outlives the subscription either way and
        // is preferable to it silently expiring next month.
        body.put("EndDate", date(mandate.getEndsOn() != null
                ? mandate.getEndsOn() : mandate.getStartsOn().plusYears(10)));
        body.put("BusinessShortCode", shortCode);
        body.put("TransactionType", till
                ? "Standing Order Customer Pay Marchant"
                : "Standing Order Customer Pay Bill");
        // 4 is a paybill, 2 is a till. Safaricom's own spelling of the
        // transaction type above is theirs, not a typo here.
        body.put("ReceiverPartyIdentifierType", till ? "2" : "4");
        // Whole units, as a string. Ratiba is not minor-unit like a card rail.
        body.put("Amount", mandate.getAmount().setScale(0, java.math.RoundingMode.HALF_UP)
                .toPlainString());
        body.put("PartyA", phoneNumber);
        body.put("CallBackURL", callbackUrl);
        body.put("AccountReference", accountReference);
        body.put("TransactionDesc", "Internet subscription");
        body.put("Frequency", frequencyCode(mandate.getFrequency()));
        return body;
    }
}
