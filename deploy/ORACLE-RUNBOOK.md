# From a blank Oracle VM to live

A step-by-step for putting SPA WiFi on **Oracle Cloud Always Free** with
HTTPS, using the scripts in this folder. Everything here is free except the
domain (~KES 1,000/year), which you need for real per-tenant subdomains.

Rough time: 60–90 minutes, most of it waiting on the image build and DNS.

---

## 0. Before you start

You need three things:

1. An **Oracle Cloud** account (free; a credit card is required for identity
   but Always Free is never charged).
2. A **domain** you control (e.g. `spawifi.co.ke` from a KENIC registrar).
3. Each ISP's **Daraja** credentials — but only when they're ready to take
   money. The platform runs without them.

---

## 1. Create the VM

Oracle Console → **Compute → Instances → Create instance**:

- **Image:** Ubuntu 22.04 (or 24.04).
- **Shape:** `VM.Standard.A1.Flex` (Ampere/ARM) — the Always Free one. Ask
  for **2 OCPU / 12 GB** to start (you may go up to 4/24). If the region says
  "out of capacity", try again later or pick another region.
- **Region:** **Johannesburg** is the closest to Kenya.
- **SSH keys:** upload your public key (or let Oracle generate one and
  download the private key).
- Create, and note the **public IP**.

## 2. Open the firewall — BOTH layers

Oracle blocks everything but SSH by default, in two places. Miss either and
HTTPS silently never works.

**a) Cloud firewall (VCN security list):** Networking → your VCN → the public
subnet → its Security List → **Add Ingress Rules**:
- Source `0.0.0.0/0`, TCP, dest port **80**
- Source `0.0.0.0/0`, TCP, dest port **443**

**b) The VM's own firewall:** SSH in (`ssh ubuntu@<public-ip>`) and run:

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

## 3. Install Docker

```bash
sudo apt-get update
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
sudo apt-get install -y git
exec su -l ubuntu          # pick up the docker group without logging out
docker compose version     # confirm the plugin is present
```

## 4. Point DNS at the VM

At your registrar's DNS, create two **A records** to the VM's public IP:

```
@   A   <public-ip>          ← the bare domain
*   A   <public-ip>          ← wildcard: every tenant subdomain at once
```

The wildcard is what makes `acme.yourdomain.co.ke` work with no DNS change
per ISP. Give it a few minutes, then check from the VM:

```bash
getent hosts spa.yourdomain.co.ke     # should print the public IP
```

## 5. Get the code onto the VM

```bash
git clone <your-repo-url> hotspot-billing
cd hotspot-billing
```

## 6. Set the certificate email and start the edge

Edit `deploy/edge/Caddyfile` — change `admin@example.com` to a real address
(Let's Encrypt emails expiry warnings there). Then:

```bash
docker network create spa-edge
docker compose -f deploy/edge/docker-compose.yml up -d
```

## 7. Stand up your first tenant

This builds the image (first run takes a few minutes), creates the ISP's
database and app, wires the subdomain, and prints the login once:

```bash
./deploy/new-tenant.sh spa spa.yourdomain.co.ke
```

Write down the `admin` and `technician` passwords it prints — they are the
only copy. Caddy will fetch an HTTPS certificate for the subdomain within a
minute or two (DNS from step 4 must be resolving for this to work).

Open `https://spa.yourdomain.co.ke` — you should get the captive portal, and
`/admin` the office login, both over HTTPS.

## 8. Check it before trusting it

```bash
./deploy/preflight.sh spa
```

It fails loudly on the things that look fine but aren't — sandbox M-Pesa, a
default password, an unreachable callback, no router configured, no backup.

## 9. Wire up the money (when the ISP hands over Daraja)

Edit `deploy/tenants/spa.env`:
- fill `MPESA_CONSUMER_KEY`, `MPESA_CONSUMER_SECRET`, `MPESA_SHORT_CODE`,
  `MPESA_PASSKEY` (and `MPESA_PAYBILL` if C2B),
- set `MPESA_BASE_URL=https://api.safaricom.co.ke`,

then re-apply:

```bash
docker compose -p spa-spa --env-file deploy/tenants/spa.env \
  -f deploy/tenant/docker-compose.yml up -d
```

In the ISP's Daraja app, register the callback:
```
https://spa.yourdomain.co.ke/api/payments/mpesa/callback
```
Only Safaricom's IP ranges may post to it (already enforced by the app).

## 10. Reach the router (WireGuard)

The app bills correctly but can't switch anyone on until it can reach their
MikroTik. Run a WireGuard tunnel from this VM to each ISP's network and point
the Routers page at the tunnel address. (Full tunnel setup is its own doc;
the VM has `NET_ADMIN`, which is exactly why a plain VM is used and not a PaaS.)

## 11. Turn on the security you're paying nothing extra for

Now that the domain is on HTTPS:

- **Change the break-glass password:** already generated per-tenant by
  `new-tenant.sh`, so nothing to do unless you set your own — just never let
  it fall back to `admin123`.
- **Force passkeys:** set `WEBAUTHN_ENROLLMENT_REQUIRED=true` in the tenant's
  `.env` and re-apply the compose command from step 9. Every staff member
  then enrols a fingerprint/face passkey on their first sign-in.

## 12. Nightly backups

```bash
crontab -e
# add:
0 3 * * * /home/ubuntu/hotspot-billing/deploy/backup.sh spa >> /home/ubuntu/backup.log 2>&1
```

Test a restore at least once — an untested backup is a guess.

---

## Adding the next ISP

One command. DNS already covers it via the wildcard:

```bash
./deploy/new-tenant.sh acme acme.yourdomain.co.ke
```

## When to stop being free

Oracle Always Free is fine to launch and prove the product. The day an ISP is
actually paying you, move to a ~€4/month VM (e.g. Hetzner) for reliability and
so Oracle can't reclaim an "idle" free instance out from under a live tenant.
The steps above are identical on any Ubuntu VM.
