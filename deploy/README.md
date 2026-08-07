# Running this for other ISPs

One ISP gets one copy of the whole stack: its own application container,
its own database, its own subdomain and its own M-Pesa credentials.
Nothing is shared between tenants except the edge proxy.

That is a deliberate choice. The application has no concept of a tenant —
there is no `tenant_id` on any of its 34 entities — so isolation is
provided by running separate copies rather than by query discipline. A
bug in the code cannot show one ISP another's subscribers, because the
other ISP's data is in a different database the process never opens.

The trade is that you upgrade N deployments instead of one. That stays
comfortable to about 10–15 tenants. Past that, see *When to change
approach* at the end.

---

## One-time setup on the server

A small VPS is enough to start: 4 GB RAM runs the proxy and roughly four
tenants, since each is a JVM plus a Postgres.

```bash
# Docker, then:
git clone <your repo> /srv/spa-billing
cd /srv/spa-billing

docker network create spa-edge
docker build -t spa-billing:latest .
docker compose -f deploy/edge/docker-compose.yml up -d
```

Put your own address in `deploy/edge/Caddyfile` — Let's Encrypt sends
certificate expiry warnings there.

Point a wildcard DNS record at the server so new tenants need no DNS work:

```
*.yourdomain.co.ke.   A   <server IP>
```

## Adding an ISP

```bash
./deploy/new-tenant.sh acme acme.yourdomain.co.ke
```

That creates the database, starts the container, writes the Caddy site
block, reloads the proxy, and prints the generated owner password **once**.
HTTPS is issued automatically the first time the domain is visited.

Then, before they can take money:

1. Get their **own** Daraja consumer key, secret, shortcode and passkey
   into `deploy/tenants/acme.env`.
2. Set `MPESA_BASE_URL=https://api.safaricom.co.ke`. Left on sandbox, the
   app will appear to work and collect nothing.
3. Register the callback in their Daraja app:
   `https://acme.yourdomain.co.ke/api/payments/mpesa/callback`
4. Re-run the compose command the script printed.

**Their money must go to their own shortcode.** Collecting into your
account and paying them out makes you a payment aggregator, which in Kenya
is CBK-licensed territory. Per-tenant credentials avoid the question
entirely — confirm the detail with someone who knows Kenyan payments law,
but never route their float through your account on a hunch.

## Reaching their router

This is the part that will actually take your time. The application opens
an outbound API connection to the MikroTik. Your server sits on the
internet; their router sits behind their NAT. Something has to bridge that.

**WireGuard from their site to your server** is the option to prefer.
RouterOS 7 speaks WireGuard natively, so no extra hardware is needed.

On your server:

```bash
# One interface serves every tenant; each gets its own address.
apt install wireguard
wg genkey | tee /etc/wireguard/server.key | wg pubkey > /etc/wireguard/server.pub
```

`/etc/wireguard/wg0.conf`:

```ini
[Interface]
Address = 10.90.0.1/24
ListenPort = 51820
PrivateKey = <server.key>

[Peer]                      # acme
PublicKey  = <their router's public key>
AllowedIPs = 10.90.0.2/32
```

On their MikroTik:

```
/interface/wireguard/add name=wg-billing listen-port=51820
/interface/wireguard/peers/add interface=wg-billing \
    public-key="<server.pub>" endpoint-address=<your server> \
    endpoint-port=51820 allowed-address=10.90.0.1/32 \
    persistent-keepalive=25
/ip/address/add address=10.90.0.2/24 interface=wg-billing
```

Then add the router in the admin at `10.90.0.2:8728`. Give each tenant a
distinct address (`10.90.0.2`, `.3`, `.4`) and record it in their env file
comments.

Two fallbacks, in descending order of comfort:

- **A small always-on box at their site** running the stack locally. Router
  access becomes trivial; upgrades mean touching every site.
- **Their API port exposed publicly**, firewalled to your server's IP only.
  Quickest to set up and the one to be most careful with — a RouterOS API
  on the open internet is a standing invitation, and an IP allowlist is the
  bare minimum.

## Day to day

```bash
# Upgrade everyone after a code change
docker build -t spa-billing:latest .
for f in deploy/tenants/*.env; do
  slug=$(basename "$f" .env)
  docker compose -p "spa-$slug" --env-file "$f" \
    -f deploy/tenant/docker-compose.yml up -d
done

# Suspend a tenant who has not paid you
docker compose -p spa-acme --env-file deploy/tenants/acme.env \
  -f deploy/tenant/docker-compose.yml stop app     # data untouched

# Logs
docker logs -f spa-acme-app-1
```

Schema changes apply themselves on start (`DDL_AUTO=update`), so upgrading
is a rebuild and a restart. That is convenient and it is also the sharpest
edge here: Hibernate will add columns but it will not rename or drop them,
and it has no rollback. **Take a backup before upgrading a live tenant** —
see below — and consider moving to Flyway migrations before you have real
money flowing through several ISPs.

## Backups

```bash
./deploy/backup.sh                 # every tenant, database and uploads
./deploy/backup.sh acme            # one
./deploy/backup.sh --prune 30      # tidy up
```

Nightly:

```
0 2 * * *  cd /srv/spa-billing && ./deploy/backup.sh >> /var/log/spa-backup.log 2>&1
```

The script exits non-zero if any tenant fails, so cron can alert you. Copy
`backups/` off the machine — a backup on the same disk as the database is
not a backup.

Restoring:

```bash
gunzip -c backups/<date>/acme/database.sql.gz \
  | docker exec -i spa-acme-db-1 psql -U spa_acme -d spa_acme
```

## What is not handled yet

Honest list, so none of it surprises you in front of a customer:

- **No tenant self-signup.** You run a script; there is no sign-up page.
  At five customers that is fine, and it means nobody provisions themselves
  by accident.
- **Nothing bills your ISPs.** The system bills *their* customers. What
  they owe *you* is currently a spreadsheet. Suspension is `stop app`.
- **Secrets sit in plain env files** readable by root on the host. Better
  than in the repository, worse than a secret manager. Keep the server
  locked down and `deploy/tenants/` out of git — it is already gitignored.
- **No cross-tenant view.** No dashboard telling you how all your ISPs are
  doing; you would look at each in turn.
- **Basic auth on every request.** Fine over HTTPS on a small deployment,
  thin for a public product. Worth moving to tokens before you scale up.
- **Data protection.** Hosting their subscribers' personal data makes you a
  processor under Kenya's Data Protection Act 2019, with obligations of its
  own. Worth a written agreement with each ISP covering what you may do
  with the data and what happens when they leave.

## When to change approach

Per-tenant deployment stops being comfortable somewhere around 10–15
tenants, when upgrades take an evening. The next step is **schema per
tenant**: one application, one Postgres, a separate schema for each ISP.
Spring supports it natively and — importantly — it needs no entity changes
at all, because each schema has its own tables and the unique constraints
stop colliding.

Skip shared tables with a `tenant_id` column unless you reach real scale.
It means touching all 34 entities and re-auditing every endpoint, and a
single forgotten `WHERE tenant_id = ?` shows one ISP another's customer
list. That is a business-ending bug, and the current design does not have
it.
