---
name: run-app
description: Launch the hotspot-billing app locally — Spring Boot backend (port 8081) plus React/Vite frontend (port 5173). Use when asked to run, start, restart, or demo the app, or to verify a change works end to end.
---

# Run the hotspot-billing app

Two processes: a Spring Boot API on **http://localhost:8081** and a Vite dev server on **http://localhost:5173** (which proxies `/api` to 8081).

## 1. Preconditions

- PostgreSQL must be running (Windows service, usually already up):
  ```powershell
  Get-Service postgresql-x64-18, postgresql-x64-17 | Select-Object Name, Status
  ```
  If both are stopped, start one: `Start-Service postgresql-x64-18`. The database is `hotspot_billing` on `localhost:5432` (user/pass default to `postgres`/`postgres`, overridable via `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`).
- Check the ports are free before launching; if an old instance is listening, kill it:
  ```powershell
  Get-NetTCPConnection -LocalPort 8081,5173 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  ```

## 2. Start the backend (run in background)

The system JDK is too old — the backend needs the Liberica JDK and the env var must be set in the same shell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\BellSoft\LibericaJDK-26'
cd C:\Users\PC\Desktop\hotspot-billing
.\mvnw.cmd spring-boot:run
```

First compile takes ~30-60s. It's ready when the log prints `Tomcat started on port 8081`. Smoke-test:

```powershell
curl.exe -s http://localhost:8081/api/plans
```

## 3. Start the frontend (run in background)

```powershell
cd C:\Users\PC\Desktop\hotspot-billing\frontend
npm run dev
```

Ready almost instantly at http://localhost:5173.

## 4. Verify

- Customer portal: http://localhost:5173 — plan cards should load from the API.
- Admin dashboard: http://localhost:5173/admin — default login `admin` / `admin123` (from `ADMIN_USERNAME`/`ADMIN_PASSWORD`).
- For screenshots use headless Edge: `& 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' --headless=new --disable-gpu --window-size=1280,1400 --screenshot="out.png" http://localhost:5173`

## Gotchas

- Port 8080 is taken by another Tomcat on this machine — the backend is deliberately on 8081 (`SERVER_PORT` to override). Don't "fix" it back to 8080.
- M-Pesa STK push needs Daraja sandbox credentials (`MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, `MPESA_PASSKEY`) and a public callback URL (ngrok) — without them the app runs fine but real payments can't complete. Voucher redemption works regardless.
- MikroTik integration is off by default (`mikrotik.enabled=false`), so no router is needed for local development.

## Stop

Kill the two listeners:

```powershell
Get-NetTCPConnection -LocalPort 8081,5173 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```
