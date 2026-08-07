package com.spalimited.hotspotbilling.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What counts as an acceptable password.
 *
 * <p>Applied wherever one is set — an owner creating an account, an owner
 * resetting somebody's, or a person changing their own — so the rule cannot
 * hold in one place and not another.
 */
public final class PasswordPolicy {

    private static final int MIN_LENGTH = 10;

    /**
     * Passwords that meet every rule and are still worthless. A short list
     * of the ones people actually reach for; it is not a substitute for the
     * length requirement, just a cheap way to catch the obvious.
     */
    private static final Set<String> OBVIOUS = Set.of(
            "password1!", "password123", "admin12345", "qwerty12345",
            "welcome123", "letmein123", "spawifi123", "changeme123");

    private PasswordPolicy() {
    }

    /**
     * Throws with everything wrong at once, rather than making somebody
     * discover the rules one rejection at a time.
     */
    public static void check(String password, String username, String fullName) {
        List<String> problems = new ArrayList<>();

        if (password == null || password.length() < MIN_LENGTH) {
            problems.add("be at least " + MIN_LENGTH + " characters");
        }
        String value = password == null ? "" : password;

        if (value.chars().noneMatch(Character::isUpperCase)) {
            problems.add("include a capital letter");
        }
        if (value.chars().noneMatch(Character::isLowerCase)) {
            problems.add("include a small letter");
        }
        if (value.chars().noneMatch(Character::isDigit)) {
            problems.add("include a number");
        }
        if (value.chars().allMatch(Character::isLetterOrDigit)) {
            problems.add("include a symbol such as ! ? # or -");
        }

        String lower = value.toLowerCase();
        if (OBVIOUS.contains(lower)) {
            problems.add("not be one people commonly guess");
        }
        // A password built from the account it protects is no protection.
        if (username != null && username.length() > 2 && lower.contains(username.toLowerCase())) {
            problems.add("not contain the username");
        }
        if (fullName != null) {
            for (String part : fullName.toLowerCase().split("\\s+")) {
                if (part.length() > 2 && lower.contains(part)) {
                    problems.add("not contain the person's name");
                    break;
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("The password must " + join(problems) + ".");
        }
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1))
                + " and " + parts.get(parts.size() - 1);
    }

    /** For the UI, so the rules are visible before someone types. */
    public static List<String> rules() {
        return List.of(
                "At least " + MIN_LENGTH + " characters",
                "A capital and a small letter",
                "A number",
                "A symbol, such as ! ? # or -",
                "Not the person's name or username");
    }
}
