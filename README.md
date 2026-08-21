# Hotspot Billing

WiFi hotspot billing system: customers on a MikroTik hotspot buy timed internet
plans via M-Pesa (Safaricom Daraja STK push) or redeem printed cash vouchers.

## Stack

- Java 21 / Spring Boot 4 (Maven) — REST API on port **8081**
- PostgreSQL (`hotspot_billing` database on localhost:5432)
- React (Vite) frontend in `frontend/` — customer portal at `/`, admin dashboard at `/admin`
- MikroTik RouterOS API (`me.legrange:mikrotik`)
- Safaricom Daraja API for Lipa na M-Pesa STK push

## How it works

1. Customer connects to WiFi and lands on the captive portal.
2. Portal calls `POST /api/payments/stk-push` with their phone number and chosen plan → they get an M-Pesa PIN prompt.
3. Safaricom posts the result to `POST /api/payments/mpesa/callback`.
4. On success a voucher is issued and pushed to the router as a hotspot user
   (username = password = voucher code, `limit-uptime` = plan duration).
5. Portal polls `GET /api/payments/{id}` until the voucher code is ready; customer logs in with it.

Cash vouchers: batch-generate with `POST /api/vouchers/generate` and print them.

## Run it

Backend (needs PostgreSQL running with a `hotspot_billing` database):

```powershell
$env:JAVA_HOME = 'C:\Program Files\BellSoft\LibericaJDK-26'
mvn spring-boot:run
```

API: http://localhost:8081 (8080 is taken by Tomcat on this machine; override with `SERVER_PORT`)

Frontend (dev server proxies `/api` to the backend):

```powershell
cd frontend
npm run dev
```

Open http://localhost:5173 (portal) and http://localhost:5173/admin (admin, default login `admin` / `admin123` — change via `ADMIN_USERNAME` / `ADMIN_PASSWORD`).

Tests run on in-memory H2, so `mvn test` needs no database.

## API

Public (captive portal):

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/plans` | List active plans |
| POST | `/api/payments/stk-push` | Start an M-Pesa payment (`{"phoneNumber":"2547XXXXXXXX","planId":1}`) |
| GET | `/api/payments/{id}` | Poll payment status / get voucher code |
| POST | `/api/payments/mpesa/callback` | Daraja async callback (Safaricom calls this) |
| POST | `/api/vouchers/{code}/activate` | Mark a voucher active on first use |

Admin (HTTP Basic auth required):

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/admin/stats` | Revenue + counts for the dashboard |
| GET | `/api/admin/payments` | Recent payments |
| GET | `/api/admin/plans` | All plans incl. inactive |
| POST | `/api/admin/plans` | Create a plan |
| PATCH | `/api/admin/plans/{id}/toggle` | Activate/deactivate a plan |
| GET | `/api/admin/vouchers` | Recent vouchers |
| POST | `/api/admin/vouchers/generate` | Batch-generate cash vouchers (`{"planId":1,"count":50}`) |

## Configuration

Set these environment variables (or edit `src/main/resources/application.properties`):

- `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, `MPESA_SHORT_CODE`, `MPESA_PASSKEY` — from the [Daraja portal](https://developer.safaricom.co.ke/)
- `MPESA_CALLBACK_URL` — public HTTPS URL to `/api/payments/mpesa/callback` (use ngrok in development)
- `MIKROTIK_PASSWORD` plus `mikrotik.host` / `mikrotik.username`; set `mikrotik.enabled=true` once your router is reachable

## Not done yet (roadmap)

- [ ] SMS delivery of voucher codes after payment
- [ ] Scheduled job to expire vouchers and clean up router users
- [ ] Serve the built React app from Spring Boot for single-artifact deployment
- [ ] MikroTik walled-garden setup so unauthenticated users can reach the portal + M-Pesa
to be done next week
