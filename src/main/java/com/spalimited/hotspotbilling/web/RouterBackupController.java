package com.spalimited.hotspotbilling.web;

import com.spalimited.hotspotbilling.domain.Router;
import com.spalimited.hotspotbilling.domain.RouterBackup;
import com.spalimited.hotspotbilling.repository.RouterBackupRepository;
import com.spalimited.hotspotbilling.repository.RouterRepository;
import com.spalimited.hotspotbilling.service.RouterConfigBackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saved router configurations. */
@RestController
@RequestMapping("/api/admin/router-backups")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('NETWORK')")
public class RouterBackupController {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneId.systemDefault());

    private final RouterRepository routers;
    private final RouterBackupRepository backups;
    private final RouterConfigBackupService backupService;

    /** Every router, with how its backup is doing. */
    @GetMapping
    public Map<String, Object> overview() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Router router : routers.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("routerId", router.getId());
            row.put("name", router.getName());
            row.put("host", router.getHost());
            row.put("enabled", router.isEnabled());
            row.put("lastBackupAt", router.getConfigBackupAt());
            row.put("error", router.getConfigBackupError());
            row.put("versions", backups.countByRouterId(router.getId()));
            backups.findFirstByRouterIdOrderByFirstSeenAtDesc(router.getId()).ifPresent(b -> {
                row.put("currentSince", b.getFirstSeenAt());
                row.put("method", b.getMethod());
                row.put("lineCount", b.getLineCount());
                row.put("byteCount", b.getByteCount());
            });
            rows.add(row);
        }
        return Map.of("routers", rows);
    }

    /** Every stored version for one router, newest first, without the text. */
    @GetMapping("/{routerId}/versions")
    public Map<String, Object> versions(@PathVariable Long routerId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RouterBackup b : backups.findByRouterIdOrderByFirstSeenAtDesc(routerId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("firstSeenAt", b.getFirstSeenAt());
            row.put("lastSeenAt", b.getLastSeenAt());
            row.put("method", b.getMethod());
            row.put("lineCount", b.getLineCount());
            row.put("byteCount", b.getByteCount());
            row.put("checksum", b.getChecksum().substring(0, 12));
            rows.add(row);
        }
        return Map.of("versions", rows);
    }

    @GetMapping("/version/{id}")
    public Map<String, Object> one(@PathVariable Long id) {
        RouterBackup b = backups.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such backup"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", b.getId());
        out.put("routerId", b.getRouterId());
        out.put("firstSeenAt", b.getFirstSeenAt());
        out.put("lastSeenAt", b.getLastSeenAt());
        out.put("method", b.getMethod());
        out.put("content", b.getContent());
        return out;
    }

    /**
     * The file itself.
     *
     * <p>Named .rsc when it came from /export, because that is what it is and a
     * RouterOS box will import it. A section read is named .txt, so nobody
     * discovers on the wrong night that the thing they downloaded was never a
     * restore file.
     */
    @GetMapping("/version/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        RouterBackup b = backups.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such backup"));
        String routerName = routers.findById(b.getRouterId())
                .map(Router::getName).orElse("router-" + b.getRouterId());
        String safe = routerName.replaceAll("[^A-Za-z0-9._-]", "-");
        String extension = "EXPORT".equals(b.getMethod()) ? "rsc" : "txt";
        String filename = safe + "-" + FILE_STAMP.format(b.getFirstSeenAt()) + "." + extension;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(b.getContent().getBytes(StandardCharsets.UTF_8));
    }

    /** What changed between two stored versions. */
    @GetMapping("/diff")
    public Map<String, Object> diff(@RequestParam Long from, @RequestParam Long to) {
        RouterBackup a = backups.findById(from)
                .orElseThrow(() -> new IllegalArgumentException("No such backup"));
        RouterBackup b = backups.findById(to)
                .orElseThrow(() -> new IllegalArgumentException("No such backup"));
        List<Map<String, String>> lines = backupService.diff(a.getContent(), b.getContent());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("added", lines.stream().filter(l -> "+".equals(l.get("mark"))).count());
        out.put("removed", lines.stream().filter(l -> "-".equals(l.get("mark"))).count());
        return out;
    }

    /** Backs one router up now. */
    @PostMapping("/{routerId}/run")
    public Map<String, Object> run(@PathVariable Long routerId) {
        Router router = routers.findById(routerId)
                .orElseThrow(() -> new IllegalArgumentException("No such router"));
        RouterConfigBackupService.Outcome outcome = backupService.backup(router);
        return Map.of("ok", outcome.ok(), "changed", outcome.changed(),
                "message", outcome.message());
    }

    /** Backs every router up now. */
    @PostMapping("/run")
    public Map<String, Object> runAll() {
        return backupService.backupAll();
    }
}
