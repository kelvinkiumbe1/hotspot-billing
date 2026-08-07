# Deploying on Render (paid)

Gets the app live on HTTPS without a VM. Good for a demo and for testing
passkeys. M-Pesa and the router tunnel are **not** covered here — they need
the always-on VM + WireGuard path (see ORACLE-RUNBOOK.md); Render is fine
until you're switching real customers on.

Two ways: the **Blueprint** (fast) or the **dashboard** (click-by-click).
Both need the repo pushed to GitHub/GitLab first.

```bash
git push            # Render deploys from your Git remote, not local files
```

---

## Option A — Blueprint (uses render.yaml at the repo root)

1. Render dashboard → **New → Blueprint** → pick this repo.
2. Render reads `render.yaml` and proposes a web service + a Postgres.
3. It will ask you to fill the `sync: false` values — see the env table below.
4. **DB_URL** needs one manual step (next section). Apply, and it builds
   (first build ~5–10 min: it compiles the UI, then the jar).

## Option B — Dashboard

1. **New → PostgreSQL** → paid plan → create. Open it and keep the
   **Connections** panel handy.
2. **New → Web Service** → pick this repo → **Runtime: Docker** (it finds the
   Dockerfile) → paid **Starter** plan → set **Health Check Path** to
   `/api/plans`.
3. Add the environment variables from the table below → **Create Web Service**.

---

## Setting DB_URL (both options)

Render's managed connection string is `postgresql://…`, but Spring needs the
`jdbc:` form. From the database's **Connections** panel take the **Internal**
hostname and database name and build:

```
DB_URL = jdbc:postgresql://<internal-hostname>:5432/<database>
```

Example: internal host `dpg-abc123-a`, database `spa` →
`jdbc:postgresql://dpg-abc123-a:5432/spa`

Username and password are wired in automatically from the database (Blueprint)
or copy them from the same panel (dashboard). The URL carries no secret, so
it's safe to paste.

## Environment variables

| Key | Value |
|-----|-------|
| `DB_URL` | `jdbc:postgresql://<internal-host>:5432/<db>` (above) |
| `DB_USERNAME` | from the database |
| `DB_PASSWORD` | from the database |
| `ADMIN_USERNAME` | `admin` |
| `ADMIN_PASSWORD` | a strong password — **never** `admin123` |
| `TECH_USERNAME` | `technician` |
| `TECH_PASSWORD` | a strong password |
| `WEBAUTHN_RP_ID` | your host, no scheme — e.g. `spa-wifi.onrender.com` |
| `WEBAUTHN_ORIGINS` | `https://spa-wifi.onrender.com` |
| `WEBAUTHN_ENROLLMENT_REQUIRED` | `false` at first; `true` to force passkeys |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | `https://spa-wifi.onrender.com` |
| `MPESA_BASE_URL` | `https://sandbox.safaricom.co.ke` (deferred) |

You won't know the exact `onrender.com` hostname until the service is created,
so: create it first, then set the three host-based vars (`WEBAUTHN_RP_ID`,
`WEBAUTHN_ORIGINS`, `APP_CORS_ALLOWED_ORIGIN_PATTERNS`) and redeploy.

## First run

- The app runs its Flyway migrations against the fresh database on boot
  (V1–V3), so the schema builds itself. No manual DB setup.
- Open `https://<service>.onrender.com/admin` and sign in with
  `admin` / the `ADMIN_PASSWORD` you set.
- Passkeys work here because Render gives real HTTPS. To force them, set
  `WEBAUTHN_ENROLLMENT_REQUIRED=true` and redeploy.

## Custom domain (optional)

Add it under the service's **Settings → Custom Domains**, point a CNAME as
Render instructs, then update `WEBAUTHN_RP_ID`, `WEBAUTHN_ORIGINS` and
`APP_CORS_ALLOWED_ORIGIN_PATTERNS` to the new domain and redeploy.

## When M-Pesa comes back

Render can't run the WireGuard tunnel to a MikroTik, and each tenant is a
separate paid service. When you're ready to take real money and switch
customers on, move to the VM path in ORACLE-RUNBOOK.md (any Ubuntu VM — a
Kenyan VPS payable by M-Pesa works). The app image is identical; only where
it runs changes.
