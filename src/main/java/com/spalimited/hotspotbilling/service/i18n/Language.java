package com.spalimited.hotspotbilling.service.i18n;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The languages this system can serve a customer in.
 *
 * <p>Chosen for where the product is actually sold rather than by speaker
 * count worldwide: English and French between them cover most of the
 * continent's ISPs, Swahili covers East Africa's customers even where the
 * operator works in English, and Portuguese covers Angola and Mozambique.
 *
 * <p>Arabic is deliberately absent. It is not a bigger dictionary — it is a
 * right-to-left layout the portal has never been built for, and shipping a
 * half-mirrored screen would be worse than not offering it.
 */
public enum Language {

    EN("en", "English", "English"),
    FR("fr", "French", "Français"),
    SW("sw", "Swahili", "Kiswahili"),
    PT("pt", "Portuguese", "Português");

    private final String code;
    private final String englishName;
    private final String ownName;

    Language(String code, String englishName, String ownName) {
        this.code = code;
        this.englishName = englishName;
        this.ownName = ownName;
    }

    public String code() {
        return code;
    }

    public String englishName() {
        return englishName;
    }

    /** What the language calls itself — how a picker should list it. */
    public String ownName() {
        return ownName;
    }

    /** English, always. Every lookup falls back here rather than to nothing. */
    public static Language fallback() {
        return EN;
    }

    public static Language of(String code) {
        if (code == null || code.isBlank()) {
            return fallback();
        }
        String wanted = code.trim().toLowerCase(Locale.ROOT);
        // "fr-CI" and "pt_BR" are the same language as far as this is
        // concerned; a region is not worth a separate dictionary here.
        int cut = Math.min(
                wanted.indexOf('-') < 0 ? wanted.length() : wanted.indexOf('-'),
                wanted.indexOf('_') < 0 ? wanted.length() : wanted.indexOf('_'));
        String base = wanted.substring(0, cut);
        for (Language language : values()) {
            if (language.code.equals(base)) {
                return language;
            }
        }
        return fallback();
    }

    /**
     * Picks a language from a browser's Accept-Language header.
     *
     * <p>Quality values are honoured, because a browser that says
     * "fr;q=0.9, en;q=0.8" means it — and a naive first-match reader would
     * agree with it here but disagree the moment the order changes.
     */
    public static Language fromAcceptHeader(String header) {
        if (header == null || header.isBlank()) {
            return fallback();
        }
        double bestQuality = -1;
        Language best = fallback();
        boolean matched = false;
        for (String part : header.split(",")) {
            String[] bits = part.trim().split(";");
            if (bits[0].isBlank()) {
                continue;
            }
            double quality = 1.0;
            for (int i = 1; i < bits.length; i++) {
                String bit = bits[i].trim();
                if (bit.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(bit.substring(2));
                    } catch (NumberFormatException ignore) {
                        quality = 0;
                    }
                }
            }
            Language candidate = of(bits[0]);
            // of() falls back to English for anything unknown, so an explicit
            // match has to be distinguished from a defaulted one — otherwise
            // "de, fr" would be read as a request for English.
            boolean exact = bits[0].trim().toLowerCase(Locale.ROOT).startsWith(candidate.code);
            if (exact && quality > bestQuality) {
                bestQuality = quality;
                best = candidate;
                matched = true;
            }
        }
        return matched ? best : fallback();
    }

    /** Every language, for a picker. */
    public static List<Map<String, String>> describeAll() {
        return java.util.Arrays.stream(values()).map(l -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("code", l.code);
            row.put("name", l.englishName);
            row.put("ownName", l.ownName);
            return row;
        }).toList();
    }
}
