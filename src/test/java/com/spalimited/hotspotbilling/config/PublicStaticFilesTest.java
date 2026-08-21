package com.spalimited.hotspotbilling.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files a browser fetches before anybody has signed in.
 *
 * <p>This exists because of a bug that looked like a design problem. The web app
 * manifest was in the public allowlist and every icon it names was not, so
 * {@code /icon-192.png} answered 401 to the install prompt. The browser cannot
 * say "that icon needs a password" — it drew a generic glyph, and the only
 * symptom anybody could report was that the wrong logo appeared when installing
 * the app. It survived a full security audit because a 401 on a PNG looks like
 * the allowlist working.
 *
 * <p>So the invariant is checked from the other end: whatever the frontend ships
 * at the web root, and whatever the manifest asks for, has to be reachable
 * without a session. Adding an icon and forgetting the allowlist now fails here.
 *
 * <p>These read the frontend's own files rather than a copy, and skip when it is
 * absent — the backend is built on its own in CI, and a test that cannot see the
 * frontend should not fail the build over it.
 */
class PublicStaticFilesTest {

    private static final Path PUBLIC_DIR = Path.of("frontend", "public");
    private static final Path MANIFEST = PUBLIC_DIR.resolve("manifest.webmanifest");

    @SuppressWarnings("unused") // referenced by name from @EnabledIf
    static boolean frontendPresent() {
        return Files.isDirectory(PUBLIC_DIR);
    }

    /** The allowlist, as literal paths — wildcards expanded enough to compare. */
    private static Set<String> allowed() {
        return new LinkedHashSet<>(Arrays.asList(SecurityConfig.PUBLIC_STATIC));
    }

    /** Whether the allowlist covers this exact root path, honouring its wildcards. */
    private static boolean covers(String path) {
        for (String rule : allowed()) {
            if (rule.equals(path)) {
                return true;
            }
            // "/favicon.*" and "/assets/**" are the only two shapes in the list.
            if (rule.endsWith("*")) {
                String prefix = rule.substring(0, rule.length() - 1);
                if (prefix.endsWith("*")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }
                if (path.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- the manifest, which is the bug that happened ---

    @Test
    @EnabledIf("frontendPresent")
    @DisplayName("every icon the manifest names is reachable without signing in")
    void manifestIconsArePublic() throws IOException {
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(MANIFEST));
        JsonNode icons = manifest.get("icons");

        // If this is empty the test would pass vacuously and the next person to
        // break the allowlist would find out from a user.
        assertThat(icons).as("manifest declares icons").isNotNull();
        assertThat(icons.size()).as("manifest declares at least one icon").isPositive();

        List<String> unreachable = new ArrayList<>();
        for (JsonNode icon : icons) {
            String src = icon.path("src").asText();
            if (!covers(src)) {
                unreachable.add(src);
            }
        }

        assertThat(unreachable)
                .as("icons the manifest asks for but SecurityConfig.PUBLIC_STATIC does not "
                        + "permit — the install prompt will draw a generic glyph instead, and "
                        + "nothing in the logs will call it an authorisation failure")
                .isEmpty();
    }

    @Test
    @EnabledIf("frontendPresent")
    @DisplayName("the manifest itself is public, or none of the icons matter")
    void manifestIsPublic() {
        assertThat(covers("/manifest.webmanifest")).isTrue();
    }

    // --- everything else the frontend puts at the web root ---

    @Test
    @EnabledIf("frontendPresent")
    @DisplayName("every file the frontend ships at the web root is reachable")
    void shippedRootFilesArePublic() throws IOException {
        List<String> unreachable = new ArrayList<>();
        try (Stream<Path> files = Files.list(PUBLIC_DIR)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String path = "/" + f.getFileName();
                if (!covers(path)) {
                    unreachable.add(path);
                }
            }
        }

        // Anything dropped into frontend/public is served at the root by design.
        // Shipping a file nobody can fetch is never what was meant.
        assertThat(unreachable)
                .as("files in frontend/public that SecurityConfig.PUBLIC_STATIC does not permit")
                .isEmpty();
    }

    // --- and the limits on that, so this file cannot be used to open things up ---

    @Test
    @DisplayName("the allowlist does not hand out the API")
    void apiIsNotBlanketPublic() {
        for (String rule : allowed()) {
            // /api/uploads/** is deliberately public: operator logos and
            // attachments are fetched by the portal before anybody signs in.
            if (rule.startsWith("/api/") && !rule.equals("/api/uploads/**")) {
                throw new AssertionError("PUBLIC_STATIC is for static files, not API routes: " + rule);
            }
        }
    }

    @Test
    @DisplayName("no rule is broad enough to cover the whole site")
    void noCatchAllRule() {
        for (String rule : allowed()) {
            assertThat(rule)
                    .as("a catch-all here would silently undo the fail-closed default")
                    .isNotEqualTo("/**").isNotEqualTo("/*");
        }
        // A bare "/" is the SPA index and matches only itself, which is why it is
        // allowed to look like a catch-all when it is not.
        assertThat(covers("/api/admin/subscribers")).isFalse();
        assertThat(covers("/actuator/env")).isFalse();
    }
}
