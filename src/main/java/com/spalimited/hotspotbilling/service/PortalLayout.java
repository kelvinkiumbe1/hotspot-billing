package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.PortalSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which blocks the portal shows, in what order, and how they look.
 *
 * <p>Not a page builder. Each of the six portal designs has its own structure and
 * a free canvas would mean either abandoning them or maintaining both, so what is
 * adjustable here is what can be adjusted without fighting them: the optional
 * blocks, and a handful of knobs that map onto CSS variables the designs already
 * read.
 *
 * <p>Everything unset means "whatever the design already does". That is the only
 * acceptable default for a screen that sells things — an operator who never opens
 * the Layout tab must see their portal exactly as it was.
 */
public final class PortalLayout {

    /**
     * Every block that can move, in the order they ship in.
     *
     * <p>Four, because four is how many there actually are on that screen. There
     * is no "recover my code" block -- it lives inside the voucher box rather
     * than beside it -- and free trial and support are not on the plans screen at
     * all. Offering those as controls would be offering something that does
     * nothing, which is worse than not offering it.
     *
     * <p>{@code plans} is in the list and cannot be hidden. It is the reason the
     * page exists, and an operator who switched it off would have a portal that
     * sells nothing and no obvious way to understand why — so it is orderable and
     * not hideable, which {@link #hidden} enforces rather than trusting the form.
     */
    public static final List<String> BLOCKS = List.of(
            "promo", "plans", "voucher", "rewards");

    /** The one that cannot be switched off, and why is in {@link #BLOCKS}. */
    public static final String REQUIRED = "plans";

    private static final Set<String> ALIGN = Set.of("left", "centre");
    private static final Set<String> LOGO = Set.of("s", "m", "l");
    private static final Set<String> FONT = Set.of("sans", "serif", "mono", "rounded");
    private static final Set<String> DENSITY = Set.of("compact", "comfortable", "spacious");

    private PortalLayout() {
    }

    /**
     * The blocks in the order to render them.
     *
     * <p>Two rules make this survive a release. A name the operator has saved that
     * this version does not know is dropped, so a block removed later does not
     * leave a hole. And a block this version knows that is missing from their
     * saved order goes on the end, so a block added later appears without the
     * operator having to re-save — in a sensible place rather than not at all.
     */
    public static List<String> order(String saved) {
        List<String> out = new ArrayList<>();
        if (saved != null && !saved.isBlank()) {
            for (String raw : saved.split(",")) {
                String name = raw.trim().toLowerCase(Locale.ROOT);
                if (BLOCKS.contains(name) && !out.contains(name)) {
                    out.add(name);
                }
            }
        }
        for (String name : BLOCKS) {
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The blocks to leave out.
     *
     * <p>{@code plans} is removed from whatever was saved rather than rejected at
     * the door, because the check has to hold for a row written before this rule
     * existed, or by a direct database edit, or by a future bug in the form. The
     * portal asks this method, so this method is where the guarantee lives.
     */
    public static Set<String> hidden(String saved) {
        Set<String> out = new LinkedHashSet<>();
        if (saved == null || saved.isBlank()) {
            return out;
        }
        for (String raw : saved.split(",")) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (BLOCKS.contains(name) && !REQUIRED.equals(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The layout, as the portal needs it.
     *
     * <p>Null for every knob the operator has not set, so the browser can tell
     * "they chose centre" from "they have not chosen" — an empty string would read
     * as the former and quietly override the design.
     */
    public static Map<String, Object> describe(PortalSettings s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", order(s.getSectionOrder()));
        out.put("hidden", hidden(s.getSectionsHidden()));
        out.put("align", oneOf(s.getContentAlign(), ALIGN));
        out.put("radius", radius(s.getCornerRadius()));
        out.put("logoSize", oneOf(s.getLogoSize(), LOGO));
        out.put("headingFont", oneOf(s.getHeadingFont(), FONT));
        out.put("density", oneOf(s.getDensity(), DENSITY));
        return out;
    }

    /**
     * Corner rounding, kept inside the range the CSS can use.
     *
     * <p>Not the door: the endpoint rejects an out-of-range radius outright with
     * a message naming the limit, because a slider cannot produce one and an API
     * caller who sent 99 should be told rather than silently given 24.
     *
     * <p>This is the second line — it guards a value that arrived from somewhere
     * that never passed through that check: a row written before the limit
     * existed, a direct database edit, a restored backup. The portal reads this
     * method, so this method is where a usable value is guaranteed.
     */
    public static Integer radius(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.max(0, Math.min(24, value));
    }

    /** The value if it is one we know, else null — which means "leave the design alone". */
    public static String oneOf(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : null;
    }

    /**
     * A submitted list of block names, cleaned for storage.
     *
     * <p>Kept in the order given, deduplicated, and unknown names dropped. Stored
     * rather than rejected so a partial list is a partial list: the operator moved
     * what they moved and {@link #order} puts the rest after it.
     */
    public static String clean(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String raw : names) {
            String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (BLOCKS.contains(name) && !kept.contains(name)) {
                kept.add(name);
            }
        }
        return kept.isEmpty() ? null : String.join(",", kept);
    }

    /** The same, for the hidden list, which additionally may never contain plans. */
    public static String cleanHidden(List<String> names) {
        String cleaned = clean(names);
        if (cleaned == null) {
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (String name : cleaned.split(",")) {
            if (!REQUIRED.equals(name)) {
                kept.add(name);
            }
        }
        return kept.isEmpty() ? null : String.join(",", kept);
    }
}
