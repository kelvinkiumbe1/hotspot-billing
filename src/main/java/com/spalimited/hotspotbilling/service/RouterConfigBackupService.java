package com.spalimited.hotspotbilling.service;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.RouterBackup;
import com.spalimited.hotspotbilling.repository.RouterBackupRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A copy of every router's configuration, kept off the router.
 *
 * <p>There was none. A MikroTik that dies takes its configuration with it, and
 * what follows is somebody rebuilding a network from memory at two in the
 * morning. This is the cheapest insurance in the system.
 *
 * <p>Only what changed is stored. A nightly snapshot of ten routers is 3,650
 * rows a year of which maybe forty differ from the row before; keeping versions
 * instead turns the table into a history of changes, which is also the more
 * useful thing to have. "What changed on this router, and when" is a question an
 * operator asks after an outage, and a pile of identical snapshots cannot answer
 * it.
 *
 * <p>Not storing a row does not mean not recording the run. An unchanged config
 * and a router nobody has reached since March look identical unless the
 * successful reach is written down, and that is the failure this whole feature
 * exists to prevent -- so every attempt updates either last_seen_at or the
 * error on the router.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouterConfigBackupService {

    /**
     * Above this, the line-by-line diff is skipped for a summary. The diff is
     * O(n*m); a 20,000-line config against another would be 400 million cells to
     * answer a question a summary answers well enough.
     */
    private static final int DIFF_LINE_LIMIT = 4000;

    private final RouterRepository routers;
    private final RouterBackupRepository backups;
    private final MikrotikService mikrotikService;
    private final AuditService audit;

    /** What one attempt did, in words the admin can show without rephrasing. */
    public record Outcome(boolean ok, boolean changed, String message, Long backupId) {
    }

    /**
     * Backs up one router.
     *
     * <p>Never throws. A router that is off, or has had its password changed, or
     * is behind a link that is down, is an ordinary Tuesday -- and it must not
     * stop the other nine being backed up.
     */
    @Transactional
    public Outcome backup(Router router) {
        Instant now = Instant.now();
        MikrotikService.ConfigExport export;
        try {
            export = mikrotikService.exportConfig(router);
        } catch (Exception e) {
            router.setConfigBackupError(trim(e.getMessage()));
            routers.save(router);
            log.warn("Config backup failed for {}: {}", router.getName(), e.getMessage());
            return new Outcome(false, false, "Could not reach " + router.getName()
                    + ": " + e.getMessage(), null);
        }

        String content = export.text() == null ? "" : export.text();
        if (content.isBlank()) {
            // Reaching a router and getting nothing back is a failure, not an
            // empty configuration. Storing it would overwrite a real backup with
            // a blank one.
            router.setConfigBackupError("The router answered but sent no configuration");
            routers.save(router);
            return new Outcome(false, false,
                    router.getName() + " answered but sent no configuration.", null);
        }

        String checksum = sha256(content);
        RouterBackup latest = backups.findFirstByRouterIdOrderByFirstSeenAtDesc(router.getId())
                .orElse(null);

        router.setConfigBackupAt(now);
        router.setConfigBackupError(null);
        routers.save(router);

        if (latest != null && latest.getChecksum().equals(checksum)) {
            latest.setLastSeenAt(now);
            backups.save(latest);
            return new Outcome(true, false,
                    router.getName() + " is unchanged since " + latest.getFirstSeenAt() + ".",
                    latest.getId());
        }

        RouterBackup saved = backups.save(RouterBackup.builder()
                .routerId(router.getId())
                .firstSeenAt(now)
                .lastSeenAt(now)
                .checksum(checksum)
                .method(export.method())
                .lineCount(countLines(content))
                .byteCount(content.getBytes(StandardCharsets.UTF_8).length)
                .content(content)
                .build());

        // Audited because a config changing is a thing somebody did, and the
        // question after an outage is which change and when.
        audit.system("router.config.changed",
                latest == null
                        ? "First configuration backup of " + router.getName()
                        : router.getName() + " configuration changed");
        log.info("Router {} configuration {} ({} lines, {})", router.getName(),
                latest == null ? "captured for the first time" : "changed",
                saved.getLineCount(), export.method());

        return new Outcome(true, true, latest == null
                ? "First backup of " + router.getName() + " taken."
                : router.getName() + " has changed since the last backup.", saved.getId());
    }

    /** Every enabled router, one after another. */
    @Transactional
    public Map<String, Object> backupAll() {
        int ok = 0;
        int changed = 0;
        List<String> failures = new ArrayList<>();
        for (Router router : routers.findAll()) {
            // A router that is switched off, or a deployment with the MikroTik
            // integration off entirely, is a setting rather than a failure.
            if (!mikrotikService.manageable(router)) {
                continue;
            }
            Outcome outcome = backup(router);
            if (outcome.ok()) {
                ok++;
                if (outcome.changed()) {
                    changed++;
                }
            } else {
                failures.add(router.getName());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backedUp", ok);
        out.put("changed", changed);
        out.put("failed", failures);
        return out;
    }

    /**
     * What changed between two versions, as a list of lines each marked kept,
     * added or removed.
     *
     * <p>A real longest-common-subsequence rather than two sets of lines, because
     * a config has repeated lines and moved blocks, and set arithmetic reports a
     * moved rule as one deletion and one addition somewhere else entirely.
     */
    public List<Map<String, String>> diff(String before, String after) {
        List<String> a = List.of(before.split("\n", -1));
        List<String> b = List.of(after.split("\n", -1));
        List<Map<String, String>> out = new ArrayList<>();

        if (a.size() > DIFF_LINE_LIMIT || b.size() > DIFF_LINE_LIMIT) {
            out.add(Map.of("mark", "note", "text",
                    "These versions are too large to compare line by line ("
                            + a.size() + " and " + b.size() + " lines)."));
            return out;
        }

        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                out.add(Map.of("mark", " ", "text", a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(Map.of("mark", "-", "text", a.get(i++)));
            } else {
                out.add(Map.of("mark", "+", "text", b.get(j++)));
            }
        }
        while (i < a.size()) {
            out.add(Map.of("mark", "-", "text", a.get(i++)));
        }
        while (j < b.size()) {
            out.add(Map.of("mark", "+", "text", b.get(j++)));
        }
        return out;
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
        }
    }

    private static String trim(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > 490 ? message.substring(0, 490) : message;
    }
}
