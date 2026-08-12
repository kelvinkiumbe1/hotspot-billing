# Phase 2 — Multi-tenancy & self-service signup (plan)

Goal: an ISP signs up on the Zidi landing page and, with no action from us, gets
a **live, isolated account** they can log into immediately (billing configured
later). This document is the plan to review **before** any code is written,
because the failure mode here — one ISP seeing another ISP's customers — is
business-ending, and worth getting right on paper first.

Today: single-tenant per deployment. `deploy/new-tenant.sh` gives each ISP its
own container + database, provisioned by hand. No `tenant_id` anywhere, no
in-app tenant context. That manual step is the only thing standing between us
and self-service — so Phase 2 is really "automate provisioning safely."

---

## The fork: two ways to get there

### Option A — Schema-per-tenant (one app, many Postgres schemas)
One app process, one Postgres. A `control` schema holds the tenant registry;
each ISP gets its own schema (`t_acme`) containing the full app schema. Hibernate
switches schema per request based on the subdomain.

- **Pros:** instant signup (just `CREATE SCHEMA` + migrate); lean on resources
  (one JVM, one Postgres); the README's recommended path; no entity changes
  (each schema has its own tables, so unique constraints don't collide).
- **Cons:** isolation now lives in *our code*, not the OS. Every request must set
  and clear a tenant context; a pooled DB connection must reset its `search_path`
  or it leaks the previous tenant's schema to the next request; **every scheduled
  job** (router monitor, subscription expiry, payment reconcile, sales digest…)
  must loop over all tenants. One bug = cross-tenant data leak.

### Option B — Automated container-per-tenant (control plane provisions the stack)
Keep today's bulletproof container+DB-per-tenant isolation, but replace the
manual `new-tenant.sh` with a **provisioning service**: signup enqueues a job
that programmatically stands up the tenant's container + DB + Caddy route (via
the Docker API), then flips the account to "ready".

- **Pros:** isolation is enforced by the OS/DB, exactly as today — a code bug
  *cannot* leak one tenant's data into another. No app changes, no per-tenant
  scheduled-job loops, no `search_path` hazard. Lowest risk by far.
- **Cons:** heavier (a JVM + Postgres per ISP); signup is **async** (~1–2 min to
  spin up — "your account is being set up, we'll email you"); needs Docker
  orchestration and more RAM as tenants grow; upgrades touch every container
  (we already have `update-all-tenants.sh`).

### Recommendation
For a solo operator handling real money with a handful-to-dozens of ISPs, **I
lean Option B**: it makes self-service signup possible *without* introducing the
data-leak risk class, and it reuses everything already built. Option A wins only
once resource cost (a stack per tenant) actually bites — i.e. at real scale
(hundreds of tenants), which is a good problem to have later. Schema-per-tenant
is the right *eventual* architecture; container-per-tenant is the right *first*
one because a solo team cannot absorb a cross-tenant leak.

**The rest of this plan covers both, with the parts that differ marked A/B.**

---

## Shared pieces (both options)

1. **Tenant registry** (a control-plane table, in the `control` schema for A or a
   small platform DB for B): `slug`, `subdomain`, `business_name`, `owner_email`,
   `status` (PROVISIONING / ACTIVE / SUSPENDED / FAILED), `created_at`, and for B
   the container/DB identifiers. This is the source of truth for who exists.
2. **Signup endpoint + page** (public, control context): collects business name,
   subdomain (validated unique, `[a-z0-9-]`), owner name/email/password. Creates
   the tenant row `PROVISIONING`, kicks off provisioning, returns the tenant URL.
3. **Wildcard DNS + TLS**: `*.zidi.co.ke` → the server; Caddy already issues certs
   per host. The subdomain identifies the tenant.
4. **Owner seeding**: the new tenant starts with one OWNER login (the signup
   details) and nothing else — no Daraja, no plans yet. "Set up billing later"
   is just: they log in and configure Payments when ready.
5. **Platform admin (us)**: a control-plane view to list/suspend/delete tenants
   and see signups. Suspension = deny login (A) or `stop` the container (B).
6. **Billing the ISPs**: still a separate concern (what they owe *us*). Out of
   scope for the first cut; note it.

---

## Option A specifics (schema-per-tenant)

- **Hibernate**: `hibernate.multiTenancy=SCHEMA` + two beans:
  - `MultiTenantConnectionProvider`: on `getConnection(tenant)` run
    `SET search_path TO <schema>`; **on release, reset** to the control schema —
    this reset is the single most important line; getting it wrong leaks data.
  - `CurrentTenantIdentifierResolver`: reads a `ThreadLocal<TenantContext>`.
- **Request resolution**: a filter maps `Host` subdomain → tenant slug → schema,
  sets the ThreadLocal, clears it in `finally`. **Fail closed**: a tenant-scoped
  request with no resolvable tenant is rejected (never falls back to a default
  schema).
- **Flyway**: the existing V1–V21 become the *tenant* migration set, run against
  each new schema on creation and on upgrade (`Flyway.configure().schemas(s)…`).
  A separate small migration set owns the `control` schema.
- **Existing data**: move today's `public` schema into a first real tenant schema
  (SPA WiFi's own), then repoint. One-off migration script.
- **Scheduled jobs**: every `@Scheduled` becomes "for each active tenant: set
  context, run, clear." Audit all of them (RouterMonitorJob, SubscriptionJob,
  PaymentReconcileJob, SalesDigestService, token purge, voucher expiry…).
- **Break-glass config admin**: today a global Basic-auth OWNER; must become
  platform-level (control context), not visible inside any tenant.
- **Isolation tests (mandatory before go-live)**: create 2 tenants, write to
  each, assert queries in A never see B; assert a no-tenant request is refused;
  assert a connection returned to the pool carries no leftover `search_path`.

### Option A sub-phases
- 2A.1 Control schema + tenant registry + Hibernate SCHEMA wiring + subdomain
  resolver + connection reset + isolation tests. *(hardest, riskiest)*
- 2A.2 Per-tenant Flyway provisioning (create schema → migrate → seed owner).
- 2A.3 Signup page + flow + redirect to subdomain login.
- 2A.4 Make all scheduled jobs tenant-aware.
- 2A.5 Migrate existing `public` data into a tenant; platform-admin view.
- 2A.6 Demo becomes a seeded demo tenant.

## Option B specifics (automated container provisioning)

- **Provisioning service**: on signup, generate the tenant env (like
  `new-tenant.sh` does today), then via the Docker Engine API: create the DB,
  `docker compose -p spa-<slug> up -d`, write the Caddy site block, reload Caddy.
  Update the registry to ACTIVE (or FAILED with the error).
- **Async + status**: signup returns "provisioning"; a small poller/webhook flips
  the landing page to "ready — log in here". Email the owner their URL.
- **Registry DB**: a tiny separate Postgres (or a control schema) the control
  plane owns; the tenant stacks are unchanged from today.
- **Upgrades**: `update-all-tenants.sh` already does this; wire it to the registry.
- **Guardrails**: cap concurrent provisions; handle spin-up failure (mark FAILED,
  alert us); resource watch (each stack ≈ JVM+Postgres).

### Option B sub-phases
- 2B.1 Tenant registry + signup endpoint/page (status PROVISIONING).
- 2B.2 Provisioning worker (Docker API: DB + compose up + Caddy reload) → ACTIVE.
- 2B.3 Signup "we're setting up your account" + ready notification/redirect.
- 2B.4 Platform-admin view (list/suspend/retry) + wire upgrades to the registry.
- 2B.5 Demo = a pre-provisioned demo tenant with DEMO_ENABLED.

---

## Cross-cutting risks (both)
- **M-Pesa callbacks** are server-to-server: each tenant already has its own
  Daraja app + per-subdomain callback URL, so the subdomain resolves the tenant.
  Confirmed compatible with both options.
- **Data protection (Kenya DPA 2019)**: hosting many ISPs' subscriber data makes
  us a processor; needs a written agreement per tenant and a deletion path.
- **Abuse**: public signup invites junk/abuse; add email verification + rate
  limiting + a manual-approval option for the first cut.

## Deploying the control plane (Option B, being built)
The control plane (`control-plane/`) runs on the Docker host beside the tenant
stacks, on its own port (8090). Point the apex/marketing domain at it and route:
- `/` and `/start` → the signup page
- `/console` → the platform-admin page (enter `ZIDI_ADMIN_TOKEN`)
- `/api/**` → the control-plane API

Set `ZIDI_PROVISIONER=SCRIPT` (default is DRY_RUN), `ZIDI_ADMIN_TOKEN`,
`ZIDI_BASE_DOMAIN`, and give it Docker access so `ScriptProvisioner` can run
`deploy/new-tenant.sh`. The landing page's "Start free"/"Start your ISP" and the
demo banner's "Create your own account" already link to `/start`.

## Status / what I need from you
Chose **B**. Built so far (dry-run verified): signup + registry + async
provisioning + credential delivery (owner picks password; passkeys optional) +
platform-admin page + signup rate limit + funnel wiring. Remaining: email the
"account ready" notice (needs SMTP), verify real Docker provisioning on the
host, and (optional) email verification on signup.
