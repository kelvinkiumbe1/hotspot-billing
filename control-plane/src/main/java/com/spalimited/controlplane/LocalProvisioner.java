package com.spalimited.controlplane;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * No-Docker provisioning for local development: instead of a container per
 * tenant, it stands up a real, isolated app instance per tenant as an ordinary
 * OS process. On signup it:
 *
 *   1. creates a fresh Postgres database   (spa_&lt;slug&gt;)
 *   2. launches the billing app jar against it on its own port, seeding the
 *      owner's chosen email + password as the admin login
 *   3. waits until that instance answers, then marks the tenant ACTIVE with a
 *      url of http://localhost:&lt;port&gt;
 *
 * The app runs Flyway on the empty database itself, so the schema and the
 * owner login are created exactly as they would be in production. This is a
 * faithful local stand-in for {@link ScriptProvisioner} (Docker) that a
 * developer can run on Windows with nothing but Java + Postgres — and it's
 * meant to be retired once the real Docker host exists.
 *
 * Enabled with {@code ZIDI_PROVISIONER=LOCAL}.
 */
@Component
@ConditionalOnProperty(name = "zidi.provisioner", havingValue = "LOCAL")
@Slf4j
public class LocalProvisioner implements Provisioner {

    private final TenantRepository tenants;
    private final String jarPath;
    private final String javaBin;
    private final int basePort;
    private final String dbAdminUrl;
    private final String dbHostUrlPrefix;
    private final String dbUser;
    private final String dbPassword;
    private final File runtimeDir;
    private final String controlUrl;
    private final String platformToken;

    /** slug -> the running app process, so we can stop it and not orphan JVMs. */
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    public LocalProvisioner(
            TenantRepository tenants,
            @Value("${zidi.local.app-jar}") String jarPath,
            @Value("${zidi.local.java-bin:java}") String javaBin,
            @Value("${zidi.local.base-port:8100}") int basePort,
            @Value("${zidi.local.db-admin-url:jdbc:postgresql://localhost:5432/postgres}") String dbAdminUrl,
            @Value("${zidi.local.db-host-url:jdbc:postgresql://localhost:5432/}") String dbHostUrlPrefix,
            @Value("${zidi.local.db-username:postgres}") String dbUser,
            @Value("${zidi.local.db-password:postgres}") String dbPassword,
            @Value("${zidi.local.runtime-dir:./local-tenants}") String runtimeDir,
            @Value("${zidi.local.control-url:http://localhost:8090}") String controlUrl,
            @Value("${zidi.platform.token:}") String platformToken) {
        this.tenants = tenants;
        this.jarPath = jarPath;
        this.javaBin = javaBin;
        this.basePort = basePort;
        this.dbAdminUrl = dbAdminUrl;
        this.dbHostUrlPrefix = dbHostUrlPrefix;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.runtimeDir = new File(runtimeDir).getAbsoluteFile();
        this.controlUrl = controlUrl;
        this.platformToken = platformToken;
    }

    @Override
    public ProvisionResult provision(Tenant tenant, String ownerPassword) {
        File jar = new File(jarPath).getAbsoluteFile();
        if (!jar.isFile()) {
            return ProvisionResult.failed(
                    "App jar not found at " + jar + ". Build it first: deploy/build-local-app.sh");
        }
        if (ownerPassword == null || ownerPassword.isBlank()) {
            return ProvisionResult.failed(
                    "No owner password available (a retry can't re-launch a local tenant; re-sign-up instead).");
        }

        String dbName = "spa_" + tenant.getSlug().replace('-', '_');
        int port = basePort + tenant.getId().intValue();

        // Persist the port now so getUrl() resolves to http://localhost:PORT
        // even while provisioning is still in flight.
        tenant.setLocalPort(port);
        tenants.save(tenant);

        try {
            createDatabaseIfAbsent(dbName);
        } catch (Exception e) {
            return ProvisionResult.failed("Could not create database " + dbName + ": " + e.getMessage());
        }

        // A stale instance for this slug (e.g. an earlier attempt) must go before
        // we bind the port again.
        stop(tenant.getSlug());

        Process process;
        try {
            process = launch(tenant.getSlug(), port, dbName, tenant.getOwnerEmail(), ownerPassword);
        } catch (Exception e) {
            return ProvisionResult.failed("Could not launch app instance: " + e.getMessage());
        }

        // Wait for the instance to migrate the fresh database and answer.
        String base = "http://localhost:" + port;
        boolean up = waitUntilUp(base, process, Duration.ofSeconds(180));
        if (!up) {
            stop(tenant.getSlug());
            return ProvisionResult.failed(
                    "The tenant app did not come up on " + base + " within 3 minutes. See "
                            + new File(runtimeDir, tenant.getSlug() + ".log") + " for details.");
        }

        log.info("Local tenant {} is up at {} (db {}, pid {})",
                tenant.getSlug(), base, dbName, process.pid());
        return ProvisionResult.ok("Running locally at " + base);
    }

    /**
     * Launches one tenant's app process against its database. `ownerPassword`
     * is set only on the first provision (to seed the owner login); on a
     * re-launch it's null — the owner already exists and StaffSeeder is
     * create-if-absent, so no credentials are needed or touched.
     */
    private Process launch(String slug, int port, String dbName, String ownerEmail, String ownerPassword)
            throws Exception {
        Files.createDirectories(runtimeDir.toPath());
        File logFile = new File(runtimeDir, slug + ".log");

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-jar", new File(jarPath).getAbsolutePath());
        pb.directory(runtimeDir);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile));
        Map<String, String> env = pb.environment();
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("DB_URL", dbHostUrlPrefix + dbName);
        env.put("DB_USERNAME", dbUser);
        env.put("DB_PASSWORD", dbPassword);
        if (ownerPassword != null && !ownerPassword.isBlank()) {
            env.put("ADMIN_USERNAME", ownerEmail);
            env.put("ADMIN_PASSWORD", ownerPassword);
        }
        env.put("MIKROTIK_ENABLED", "false");
        env.put("DEMO_ENABLED", "false");
        // Platform-billing link: lets the tenant collect its Zidi fee via the
        // control plane. Token/URL are shared; the slug identifies the invoice.
        env.put("ZIDI_TENANT_SLUG", slug);
        env.put("ZIDI_CONTROL_URL", controlUrl);
        if (platformToken != null && !platformToken.isBlank()) {
            env.put("ZIDI_PLATFORM_TOKEN", platformToken);
        }

        Process process = pb.start();
        running.put(slug, process);
        return process;
    }

    /** True when this tenant's app is answering on its port. */
    public boolean isUp(Tenant t) {
        return t.getLocalPort() != null && responds("http://localhost:" + t.getLocalPort() + "/api/plans");
    }

    /**
     * Local tenants are plain processes, so a control-plane restart leaves them
     * dead while the registry still says ACTIVE. Rather than re-launch every
     * historical tenant at boot (a resource storm on a dev box), we wake one
     * lazily when someone actually signs into it. Fire-and-forget: starts the
     * process if it isn't already up or starting, and returns; readiness is
     * polled by the caller via {@link #isUp(Tenant)}.
     */
    public void startIfDown(Tenant t) {
        if (t.getLocalPort() == null) return;
        Process existing = running.get(t.getSlug());
        if (existing != null && existing.isAlive()) return; // already starting/running
        if (isUp(t)) return; // survived / already answering
        String dbName = "spa_" + t.getSlug().replace('-', '_');
        try {
            createDatabaseIfAbsent(dbName);
            launch(t.getSlug(), t.getLocalPort(), dbName, t.getOwnerEmail(), null);
            log.info("Waking local tenant {} on port {}", t.getSlug(), t.getLocalPort());
        } catch (Exception e) {
            log.warn("Could not wake local tenant {}: {}", t.getSlug(), e.getMessage());
        }
    }

    /** Quick single-shot check that something is answering on a URL. */
    private boolean responds(String url) {
        try {
            HttpResponse<Void> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
                    .send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** CREATE DATABASE is autocommit-only and can't run in a transaction. */
    private void createDatabaseIfAbsent(String dbName) throws Exception {
        try (Connection conn = DriverManager.getConnection(dbAdminUrl, dbUser, dbPassword)) {
            conn.setAutoCommit(true);
            try (PreparedStatement check =
                         conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                check.setString(1, dbName);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        log.info("Database {} already exists — reusing it", dbName);
                        return;
                    }
                }
            }
            // dbName is derived from a validated slug ([a-z0-9-]), so it can't
            // carry SQL metacharacters; quote it defensively all the same.
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE DATABASE \"" + dbName + "\"");
            }
            log.info("Created database {}", dbName);
        }
    }

    private boolean waitUntilUp(String base, Process process, Duration timeout) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/plans"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return false; // it crashed on startup — no point waiting
            }
            try {
                HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) return true;
            } catch (Exception ignored) {
                // not listening yet
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Stop a tenant's app process if we're tracking one. */
    public void stop(String slug) {
        Process p = running.remove(slug);
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    /** Don't leave tenant JVMs running when the control plane stops. */
    @PreDestroy
    void shutdownAll() {
        running.values().forEach(p -> {
            if (p.isAlive()) p.destroyForcibly();
        });
    }
}
