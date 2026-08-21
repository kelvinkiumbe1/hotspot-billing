# How big can one Zidi get?

Every ISP procurement asks this on the first call, and until now the honest
answer was "we have not measured". This is the measurement, the ceiling we
found, and what we did about it.

Re-run it yourself:

```bash
python deploy/loadtest.py --base https://your-server --seconds 20 --workers 24
```

Standard library only, so it runs on the box you are about to buy without
installing anything.

## The measurements

One instance, one PostgreSQL, both on the same laptop — a 24-core Windows
workstation running the app under Liberica JDK 26 and Postgres locally. A real
server will do better; a shared 2 vCPU VPS will do worse. The shape is what
matters, not the absolute figures.

The mix is weighted the way a real day is: the captive portal is hit far more
often than an admin page.

| Book size | Concurrency | Throughput | Overview p95 | Subscribers p95 | Portal p95 |
|---|---|---|---|---|---|
| 24 customers | 24 | 180 req/s | 496 ms | 283 ms | 257 ms |
| 5,024 customers | 24 | **6 req/s** | **12,014 ms** | 8,539 ms | 2,780 ms |
| 5,024 customers *(after the fixes below)* | 24 | 61 req/s | 1,534 ms | 1,567 ms | 409 ms |
| 5,024 customers *(after the fixes below)* | 8 | 70 req/s | 471 ms | 590 ms | 50 ms |

Read the second row first. **At five thousand customers the product fell over**
— thirty times slower than at twenty-four, with the morning overview taking
twelve seconds and the public captive portal dragged down with it because the
database and CPU were saturated. That is the number that would have been found
by the first customer to grow, not by us.

## What was actually wrong

Two things, both invisible at development scale.

**The ledger asked one question per customer.** `LedgerService.outstanding()`
built a complete statement for every subscriber — invoices, payments,
adjustments, sorted into a running balance — purely to read the last row off the
bottom. At five thousand customers that is roughly fifteen thousand queries,
every time anybody opened the overview. It now asks the database for three
grouped sums and does the arithmetic once. The closing balance does not depend on
the ordering a statement needs, only on the totals, so the two are equivalent by
construction — and `LedgerBalanceTest` asserts that customer by customer,
including cancelled invoices, failed payments and every kind of adjustment.

Overview, measured on its own: **2.38 s → 0.59 s**.

**The overview read every payment ever taken** to draw a fortnight's sparkline,
then filtered in Java. Now one grouped query per payment table over a bounded
range. Same for the live-session list, which scanned the whole book to find the
handful of people online.

Together: **6 req/s → 61 req/s** at the same book size, overview p95 from twelve
seconds to one and a half.

## The ceiling that is still there

`GET /api/admin/subscribers` returns **every** subscriber, unpaginated. At 5,024
customers that is a **4.1 MB** JSON response, and it is now the slowest thing
left. It is fast enough to be usable at that size and it will not be at twenty
thousand.

Fixing it properly means paginating the endpoint and teaching the subscriber
screen to page, filter and search server-side — a change to the API shape and the
UI, not a query tweak. It is the next piece of work on this file, and it is worth
knowing before you sell to an ISP with a five-figure book.

**Take this as the supported figure: one instance comfortably serves an ISP of
around 5,000 subscribers.** Beyond that, page the subscriber list first.

## Do not use HTTP Basic for anything automated

The first run of the load test measured 57 req/s. The same run with a session
token measured 180. The difference is bcrypt: HTTP Basic re-verifies the
password hash on **every single request**, and a password hash is slow on
purpose.

The browser UI already signs in once and uses a session token, so this does not
affect real users. It matters for scripts, monitoring and integrations: sign in
once with `POST /api/auth/login` and send `Authorization: Bearer <token>`, or you
will measure our password hashing rather than our product.

## Sizing

- **Up to ~1,000 subscribers** — 2 vCPU, 4 GB, Postgres on the same box. This is
  most ISPs.
- **1,000 to 5,000** — 4 vCPU, 8 GB, and give Postgres its own disk. Watch the
  overview and the subscriber list.
- **Beyond 5,000** — page the subscriber list first (see above). Then separate
  the database onto its own machine.

Postgres will not be the first thing to hurt. The application and JSON
serialisation are.

## Staying up

Zidi is a stateless Spring Boot jar apart from two things, and both of them
decide the shape of any high-availability setup:

1. **Scheduled jobs.** Invoicing, dunning, win-back, the router poll, the revenue
   sweep and the rest run on `@Scheduled` inside the application. **Two instances
   means every job runs twice** — two invoices, two dunning texts, two STK pushes
   at the same customer. There is no leader election yet. Until there is, run
   exactly one instance that has jobs enabled.
2. **In-memory state.** The ACS keeps CWMP sessions in a map and the WhatsApp
   bots keep conversations in one. Both are short-lived and both are lost on
   restart, which is survivable; but a second instance behind a load balancer
   would only see half of them, so those flows need sticky sessions or a single
   instance.

That gives one honest recommendation today:

- **One application instance.** Run it under systemd or Docker with a restart
  policy. Recovery is a process restart, which takes a few seconds.
- **PostgreSQL with streaming replication** to a warm standby if the business
  needs it. The database is the part that holds anything you cannot rebuild.
- **`deploy/backup.sh` on a schedule**, off-site. It verifies its own dump by
  restoring it into a scratch database and counting tables, then reports to
  `POST /api/admin/ops/backup-report`; `BackupWatchService` raises an alert if a
  night goes by without one. An unverified backup is not a backup.
- **An external watchdog.** Set `OpsSettings.heartbeatUrl` — a dead application
  cannot report that it is dead.

**Active-active is not supported yet.** Saying otherwise would be selling
something that duplicates an ISP's invoices in its second week. Making it true
needs a distributed lock on the scheduler (a Postgres advisory lock is enough)
and either sticky sessions or shared session state. Neither is difficult; neither
is done.

## What to watch in production

- Overview and subscriber-list p95 — the two that grow with the book.
- Postgres connection-pool saturation.
- Job heartbeats on the System Health screen; a stale one means a job died
  silently.
- Backup age.

## Method

`deploy/loadtest.py`, 24 worker threads unless stated, 12–15 seconds per run,
after a warm-up pass. Percentiles are per endpoint. p95 is the number to hold
yourself to — p50 flatters everything. The 5,024-customer book was seeded
directly into `subscribers` and removed afterwards; the figures above come from
runs against real PostgreSQL, not a mock.
