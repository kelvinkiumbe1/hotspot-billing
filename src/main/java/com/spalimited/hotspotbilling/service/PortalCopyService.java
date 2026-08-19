package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalCopy;
import com.spalimited.hotspotbilling.repository.PortalCopyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The operator's own wording, where they have written any.
 *
 * <p>Returns overrides only — never the defaults. The portal and the backend each
 * already own a full set of strings, and duplicating them here would mean two
 * copies to keep in step and a portal that silently kept saying last release's
 * words. So this answers "what has been changed" and the caller lays it over what
 * it already has.
 *
 * <p>That direction matters for a reason worth stating: a string added in a
 * future release has no row here, so it appears in its own language rather than
 * as a blank or a key name. An operator cannot break tomorrow's portal by having
 * customised today's.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortalCopyService {

    /**
     * The longest a single line may be.
     *
     * <p>Generous, because a terms line or a support message is genuinely long
     * and truncating an operator's own words at save time is worse than letting
     * the portal wrap them. Bounded at all because this text is rendered on a
     * public page and an unbounded field is a way to make that page unusable.
     */
    private static final int MAX_LENGTH = 2000;

    /** What a key is allowed to look like: what the string tables actually use. */
    private static final java.util.regex.Pattern KEY =
            java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*)*");

    private final PortalCopyRepository repository;

    /** Every override for one language, as key to text. Empty is the normal case. */
    @Transactional(readOnly = true)
    public Map<String, String> forLanguage(String language) {
        String lang = normalise(language);
        Map<String, String> out = new LinkedHashMap<>();
        for (PortalCopy row : repository.findByLanguage(lang)) {
            out.put(row.getCopyKey(), row.getText());
        }
        return out;
    }

    /** Every override, grouped by language, for the admin screen to show. */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> all() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (PortalCopy row : repository.findAll()) {
            out.computeIfAbsent(row.getLanguage(), k -> new LinkedHashMap<>())
                    .put(row.getCopyKey(), row.getText());
        }
        return out;
    }

    /**
     * Saves one language's overrides, replacing what was there.
     *
     * <p>A blank or absent value deletes the row rather than storing an empty
     * string, which is what makes "clear the box to put it back" work — and stops
     * the table filling with rows that mean nothing.
     *
     * <p>Only the language being edited is touched. Two people rewriting
     * different languages cannot overwrite each other, and an operator who speaks
     * one of their four languages can leave the others alone without having to
     * resubmit them.
     */
    @Transactional
    public int save(String language, Map<String, String> edits, String by) {
        String lang = normalise(language);
        if (edits == null) {
            return 0;
        }
        int changed = 0;
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            String key = edit.getKey() == null ? "" : edit.getKey().trim();
            if (!KEY.matcher(key).matches() || key.length() > 120) {
                // Silently skipped rather than failing the save. This arrives as
                // a map from a form; one unrecognisable key must not cost an
                // operator the twenty edits they made beside it.
                log.warn("Ignoring a portal copy key that is not a key: {}",
                        key.length() > 40 ? key.substring(0, 40) + "…" : key);
                continue;
            }
            String text = edit.getValue() == null ? "" : edit.getValue();
            var existing = repository.findByLanguageAndCopyKey(lang, key);
            if (text.isBlank()) {
                // Back to the built-in wording.
                if (existing.isPresent()) {
                    repository.delete(existing.get());
                    changed++;
                }
                continue;
            }
            if (text.length() > MAX_LENGTH) {
                text = text.substring(0, MAX_LENGTH);
            }
            PortalCopy row = existing.orElseGet(() -> PortalCopy.builder()
                    .language(lang).copyKey(key).build());
            if (text.equals(row.getText())) {
                continue;
            }
            row.setText(text);
            row.setUpdatedBy(by);
            repository.save(row);
            changed++;
        }
        return changed;
    }

    /** Puts one language back to how it shipped. */
    @Transactional
    public int reset(String language) {
        String lang = normalise(language);
        List<PortalCopy> rows = repository.findByLanguage(lang);
        repository.deleteAll(rows);
        return rows.size();
    }

    /**
     * The language code, in the shape the rows use.
     *
     * <p>Upper case, because the portal's own string tables are keyed EN / SW /
     * FR / PT and a lower-case row would simply never be found — a silent
     * failure that would look like the save not working.
     */
    private static String normalise(String language) {
        if (language == null || language.isBlank()) {
            return "EN";
        }
        return language.trim().toUpperCase(Locale.ROOT);
    }
}
