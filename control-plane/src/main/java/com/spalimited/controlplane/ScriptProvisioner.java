package com.spalimited.controlplane;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Real provisioning: runs deploy/new-tenant.sh on the Docker host, which
 * creates the tenant's database, starts its container and adds its Caddy route.
 * Reused rather than reimplemented so the one, already-tested provisioning path
 * serves both the CLI and self-service signup.
 */
@Component
@ConditionalOnProperty(name = "zidi.provisioner", havingValue = "SCRIPT")
@Slf4j
public class ScriptProvisioner implements Provisioner {

    private final File repoDir;

    public ScriptProvisioner(@Value("${zidi.repo-dir}") String repoDir) {
        this.repoDir = new File(repoDir).getAbsoluteFile();
    }

    @Override
    public ProvisionResult provision(Tenant tenant, String ownerPassword) {
        File script = new File(repoDir, "deploy/new-tenant.sh");
        if (!script.isFile()) {
            return ProvisionResult.failed("new-tenant.sh not found at " + script);
        }
        // Args come from a validated slug/subdomain (see SignupService), so they
        // cannot inject flags or shell metacharacters here.
        ProcessBuilder pb = new ProcessBuilder(
                "bash", "deploy/new-tenant.sh", tenant.getSlug(), tenant.getSubdomain());
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        // The owner's chosen login goes in via the environment (not argv, so it
        // never shows in a process list). The script uses it as the owner's
        // bootstrap password and forces passkey enrolment on first sign-in.
        if (ownerPassword != null && !ownerPassword.isBlank()) {
            pb.environment().put("OWNER_EMAIL", tenant.getOwnerEmail());
            pb.environment().put("OWNER_PASSWORD", ownerPassword);
        }
        StringBuilder output = new StringBuilder();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(10, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return ProvisionResult.failed("Provisioning timed out after 10 minutes");
            }
            if (process.exitValue() != 0) {
                log.warn("Provisioning of {} failed:\n{}", tenant.getSlug(), output);
                return ProvisionResult.failed(tail(output.toString()));
            }
            log.info("Provisioned tenant {} at {}", tenant.getSlug(), tenant.getSubdomain());
            return ProvisionResult.ok("Stack created.");
        } catch (Exception e) {
            log.warn("Provisioning of {} errored: {}", tenant.getSlug(), e.getMessage());
            return ProvisionResult.failed(e.getMessage());
        }
    }

    /** Keep the last, most relevant lines of a long script log for the status. */
    private static String tail(String text) {
        String[] lines = text.strip().split("\n");
        int from = Math.max(0, lines.length - 6);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }
}
