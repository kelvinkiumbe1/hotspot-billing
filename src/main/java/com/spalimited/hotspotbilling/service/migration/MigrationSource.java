package com.spalimited.hotspotbilling.service.migration;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The systems an ISP is likely to be leaving, and what they call things.
 *
 * <p>Every one of these exports a CSV and every one names the same handful of
 * columns differently — and differently again between versions. So a field is
 * described by a list of candidate headings rather than one, matched with
 * punctuation and case thrown away. A mapping that only works on the version the
 * author happened to see is worse than no mapping, because it fails halfway
 * through somebody's book.
 *
 * <p>Order matters within a field: the first heading present wins, so the most
 * specific candidate is listed first. {@code servicePlanName} beats {@code name}
 * because a UISP service export has both and only one of them is the tariff.
 */
public enum MigrationSource {

    /**
     * Splynx. Customers and internet services are usually exported separately;
     * this reads the combined view an operator gets from the services report,
     * which carries the customer's name and login alongside the tariff.
     */
    SPLYNX(Map.ofEntries(
            Map.entry(Field.EXTERNAL_ID, List.of("customer_id", "customerid", "id")),
            Map.entry(Field.FULL_NAME, List.of("customer_name", "name", "full_name")),
            Map.entry(Field.PHONE, List.of("phone", "mobile", "phone_number")),
            Map.entry(Field.USERNAME, List.of("login", "username", "pppoe_login")),
            Map.entry(Field.PASSWORD, List.of("password", "pppoe_password")),
            Map.entry(Field.PLAN, List.of("tariff", "tariff_name", "plan", "description")),
            Map.entry(Field.PRICE, List.of("unit_price", "price", "total_price", "recurring_price")),
            Map.entry(Field.STATIC_IP, List.of("ipv4", "ip", "ip_address")),
            Map.entry(Field.STATUS, List.of("status", "service_status")),
            Map.entry(Field.BALANCE, List.of("balance", "deposit")),
            Map.entry(Field.PAID_UNTIL, List.of("end_date", "expires", "deadline", "billing_due")))),

    /**
     * UISP (formerly UNMS/UCRM). Splits a person's name across two columns and
     * calls a company a client too, so the name is assembled rather than read.
     */
    UISP(Map.ofEntries(
            Map.entry(Field.EXTERNAL_ID, List.of("clientid", "client_id", "id")),
            Map.entry(Field.FULL_NAME, List.of("companyname", "company_name", "client_name")),
            Map.entry(Field.FIRST_NAME, List.of("firstname", "first_name")),
            Map.entry(Field.LAST_NAME, List.of("lastname", "last_name")),
            Map.entry(Field.PHONE, List.of("phone", "mobile", "contact_phone")),
            Map.entry(Field.USERNAME, List.of("pppoeusername", "pppoe_username", "username", "login")),
            Map.entry(Field.PASSWORD, List.of("pppoepassword", "pppoe_password", "password")),
            Map.entry(Field.PLAN, List.of("serviceplanname", "service_plan_name", "serviceplan",
                    "tariff", "plan")),
            Map.entry(Field.PRICE, List.of("price", "total", "monthly_price")),
            Map.entry(Field.STATIC_IP, List.of("ipranges", "ip_ranges", "ip", "ipv4")),
            Map.entry(Field.STATUS, List.of("status", "servicestatus", "service_status")),
            Map.entry(Field.BALANCE, List.of("accountbalance", "account_balance", "balance")),
            Map.entry(Field.PAID_UNTIL, List.of("activeto", "active_to", "validto", "end_date")))),

    /**
     * Radius Manager, which is old, widespread in this market, and speaks in
     * abbreviations. Its expiry is the one field an operator most wants carried
     * over, because it is what decides who is still on.
     */
    RADIUS_MANAGER(Map.ofEntries(
            Map.entry(Field.EXTERNAL_ID, List.of("id", "uid")),
            Map.entry(Field.FULL_NAME, List.of("firstname", "name", "owner")),
            Map.entry(Field.LAST_NAME, List.of("lastname")),
            Map.entry(Field.PHONE, List.of("mobile", "phone", "cellphone")),
            Map.entry(Field.USERNAME, List.of("username", "login")),
            Map.entry(Field.PASSWORD, List.of("password")),
            Map.entry(Field.PLAN, List.of("srvname", "service", "srvid", "plan")),
            Map.entry(Field.PRICE, List.of("unitprice", "price")),
            Map.entry(Field.STATIC_IP, List.of("staticipaddress", "ip", "framedip")),
            Map.entry(Field.STATUS, List.of("enableuser", "status", "enabled")),
            Map.entry(Field.BALANCE, List.of("credits", "balance")),
            Map.entry(Field.PAID_UNTIL, List.of("expiration", "expiry", "expiredate")))),

    /**
     * Anything else. Deliberately broad, because the alternative for an ISP on a
     * system nobody has heard of is to be told no.
     */
    GENERIC(Map.ofEntries(
            Map.entry(Field.EXTERNAL_ID, List.of("id", "external_id", "ref", "account")),
            Map.entry(Field.FULL_NAME, List.of("name", "full_name", "customer", "customer_name")),
            Map.entry(Field.FIRST_NAME, List.of("firstname", "first_name")),
            Map.entry(Field.LAST_NAME, List.of("lastname", "last_name", "surname")),
            Map.entry(Field.PHONE, List.of("phone", "mobile", "msisdn", "phone_number", "cell")),
            Map.entry(Field.USERNAME, List.of("username", "login", "pppoe", "pppoe_username")),
            Map.entry(Field.PASSWORD, List.of("password", "secret", "pppoe_password")),
            Map.entry(Field.PLAN, List.of("plan", "package", "tariff", "product", "service")),
            Map.entry(Field.PRICE, List.of("price", "amount", "monthly", "fee", "monthly_fee")),
            Map.entry(Field.STATIC_IP, List.of("ip", "ipv4", "ip_address", "static_ip")),
            Map.entry(Field.STATUS, List.of("status", "state", "active", "enabled")),
            Map.entry(Field.BALANCE, List.of("balance", "credit", "owing", "arrears")),
            Map.entry(Field.PAID_UNTIL, List.of("expires", "expiry", "paid_until", "end_date",
                    "valid_until", "deadline"))));

    /** The things Zidi needs to know about a customer, whatever they were called. */
    public enum Field {
        EXTERNAL_ID, FULL_NAME, FIRST_NAME, LAST_NAME, PHONE, USERNAME, PASSWORD,
        PLAN, PRICE, STATIC_IP, STATUS, BALANCE, PAID_UNTIL
    }

    private final Map<Field, List<String>> aliases;

    MigrationSource(Map<Field, List<String>> aliases) {
        this.aliases = aliases;
    }

    /**
     * Reads one field out of a row.
     *
     * <p>The row's own headings are normalised the same way as the candidates, so
     * {@code "PPPoE Username"}, {@code "pppoe_username"} and {@code "pppoeUsername"}
     * are all the same column. Falls through to the GENERIC candidates, because an
     * operator who exported from Splynx but tidied the headings by hand should
     * still be understood.
     */
    public String read(Map<String, String> row, Field field) {
        String found = readFrom(row, aliases.get(field));
        if (found == null && this != GENERIC) {
            found = readFrom(row, GENERIC.aliases.get(field));
        }
        return found;
    }

    private static String readFrom(Map<String, String> row, List<String> candidates) {
        if (candidates == null || row == null) {
            return null;
        }
        for (String candidate : candidates) {
            String want = normalise(candidate);
            for (Map.Entry<String, String> entry : row.entrySet()) {
                if (want.equals(normalise(entry.getKey()))) {
                    String value = entry.getValue();
                    if (value != null && !value.isBlank()) {
                        return value.trim();
                    }
                }
            }
        }
        return null;
    }

    /** Case, spaces, underscores and hyphens all thrown away. */
    private static String normalise(String header) {
        if (header == null) {
            return "";
        }
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * The customer's name, however this system chose to break it up.
     *
     * <p>A company column wins over a person's name when both are present, which
     * is how UISP files a business account — and billing a business under the
     * name of whoever signed the form is the kind of small wrongness an operator
     * has to apologise for.
     */
    public String readName(Map<String, String> row) {
        String full = read(row, Field.FULL_NAME);
        if (full != null && !full.isBlank()) {
            return full;
        }
        String first = read(row, Field.FIRST_NAME);
        String last = read(row, Field.LAST_NAME);
        String joined = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return joined.isBlank() ? null : joined;
    }
}
