# Moving Zidi to a new machine

Short answer to the question that matters: **a fresh clone plus `mvnw spring-boot:run`
does not give you a working app.** It gives you a working API with no user interface
at all — every screen blank, the captive portal included.

That is not a bug, it is what `.gitignore` says. `src/main/resources/static/` is
ignored, and `pom.xml` contains nothing that builds or copies the frontend. So the
directory Spring serves the whole UI from has **zero files in git**:

```
$ git ls-tree -r --name-only HEAD | grep -c "^src/main/resources/static"
0
```

The frontend is a separate npm build that has to be run and copied in by hand. Miss
it and the failure is quiet in the worst way — the backend answers every endpoint
correctly, so it looks like a UI problem rather than a missing build.

## What you need installed

Versions this project is currently developed on, not minimums:

| | Version here | Notes |
|---|---|---|
| JDK | Liberica 26 | `pom.xml` targets Java **21**, so 21+ is fine. The system default is often older — see the `JAVA_HOME` note below. |
| Node | 24.16.0 | Any current LTS works. |
| npm | 11.17.0 | Ships with Node. |
| PostgreSQL | 18.4 | 15+ is fine. |

## First run, in order

```bash
git clone https://github.com/kelvinkiumbe1/hotspot-billing.git
cd hotspot-billing
```

**1. Create the database.** Flyway builds all 105 tables on first boot from the 86
migrations in the repo, but it will not create the database itself:

```bash
createdb -U postgres hotspot_billing
```

Defaults are `jdbc:postgresql://localhost:5432/hotspot_billing`, user `postgres`,
password `postgres`, all overridable via `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`.

**2. Build the frontend and put it where Spring serves it.** This is the step that
is easy to skip:

```bash
cd frontend
npm install
npm run build
cd ..
rm -rf src/main/resources/static
cp -r frontend/dist src/main/resources/static
```

`deploy/build-local-app.sh` does this and then packages a jar — but it calls maven
**without** `-o`, so run the steps by hand if you are offline.

**3. Start it.**

```bash
export JAVA_HOME='/path/to/jdk21+'     # PowerShell: $env:JAVA_HOME = 'C:\...'
./mvnw spring-boot:run
```

Then http://localhost:8081. Admin at `/admin`, default login `admin` / `admin123`
from `ADMIN_USERNAME` / `ADMIN_PASSWORD`.

Port **8081**, not 8080 — 8080 was occupied on the old machine and the config
followed. Override with `SERVER_PORT` or `PORT` if you would rather have 8080 back
on the new one; nothing depends on 8081 except habit.

### For frontend work, add the dev server

```bash
cd frontend && npm run dev      # :5173, proxies /api to :8081
```

Only needed for hot reload. The baked build at :8081 is the real thing, and it is
the only one you should ever measure page weight against — see
`local-dev-gotchas` in the Claude memory for why the dev server lies about that.

## What git does not carry

Three things, in descending order of how much it hurts to lose them.

### 1. `local.properties` — the M-Pesa credentials

Gitignored, and correctly so: it holds live Daraja sandbox keys.

```
mpesa.consumer-key
mpesa.consumer-secret
mpesa.passkey
mpesa.callback-url
mpesa.callback-allowed-ips
```

**Do not move this file at all.** Re-read the keys from
developer.safaricom.co.ke on the new machine: consumer key and secret from your
app's Keys page, the passkey from the sandbox test credentials page. It takes a
couple of minutes, and a credential that never leaves the machine cannot be
intercepted, left on a shared drive, or forgotten in a bundle somebody meant to
delete. Moving a secret you can simply re-fetch is a risk taken for no benefit.

`mpesa.callback-url` is a dead ngrok hostname regardless — ngrok issues a new one
every restart, so that line needed re-pasting either way.

Without this file the app boots and runs: portal, admin, voucher redemption all
work. Only live STK pushes fail.

`handover-bundle.sh` writes `MPESA-CREDENTIALS.txt` listing exactly what to
re-enter and where each value comes from, instead of copying the file.

### 2. `uploads/` — the operator's branding

Five files, 1.7 MB, and the database points straight at them:
`portal_settings.logo_filename` is currently
`c5604d3b-c366-4f8b-9a00-48e65e8b9c1f.png`. Copy the folder or the portal renders a
broken logo — and only if you also bring the database, since a fresh one has no
reference to fix.

### 3. The database — 17 MB, 105 tables, 86 migrations applied

You have a real choice here:

- **Start clean.** Skip the dump entirely. Flyway builds the schema, and you
  re-enter plans, branding and settings through the admin. Fine if the current data
  is test data, which much of it is.
- **Bring it.** `pg_dump -U postgres -Fc hotspot_billing > zidi.dump`, then
  `pg_restore -U postgres -d hotspot_billing zidi.dump` on the new machine. Do this
  if you want the plans, customers and settings as they stand.

If you bring the database, bring `uploads/` too. They only make sense together.

### What you can safely leave behind

| | Why |
|---|---|
| `target/`, `frontend/node_modules/`, `frontend/dist/`, `src/main/resources/static/` | All rebuilt by the steps above. |
| `data/hotspot-billing.mv.db` | A stale H2 file from before PostgreSQL. Nothing reads it. |
| `control-plane/local-tenants/` | 36 log files from tenant tests. Logs, not state. |
| The `.mp4`s and "Logo maker project" files | Marketing scratch, not part of the app. |

## Getting the bundle across without a USB stick

`deploy/handover-bundle.sh` ends by tarring everything into a single
AES-256-encrypted file, precisely so it can travel over something you do not
fully trust. Encrypted rather than merely compressed because of the database
dump: it holds real customer phone numbers — personal data under Kenya's Data
Protection Act 2019, not test rows — and that should not sit unprotected on a
shared drive, in a cloud account's deleted-items, or on an open network share.

**Over the local network**, which is the simplest option when both machines are
in the same place and involves no third party at all:

```bash
# old machine
cd ~/Desktop && python -m http.server 8000

# new machine, same wifi
curl -O http://<old-machine-ip>:8000/zidi-handover.tar.gz.enc
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \
  -in zidi-handover.tar.gz.enc | tar -xzf -
```

The script prints the machine's actual LAN address, so you do not have to go
looking for it. **Stop the server the moment the download finishes** — it is
unauthenticated, and anything else on that network can reach it.

**If the machines are not on the same network**, the encryption is what makes the
alternatives acceptable: put the `.enc` file through OneDrive, Google Drive, or
email it to yourself, and send the passphrase by a *different* route — a
different app, or read it out loud. The file and its key travelling together
defeats the point.

Either way the passphrase is typed, never stored. Get it wrong on the far side
and `openssl` says `bad decrypt` and stops, rather than handing you a corrupt
archive that looks like it worked.

## Bringing Claude Code's memory along

This is the part with no warning attached, so it is worth being explicit: **the
accumulated project knowledge does not live in the repo.**

What travels with git: `.claude/skills/run-app/SKILL.md`, and that is all.

What does not, and is worth carrying — 12 files, 96 KB:

```
C:\Users\PC\.claude\projects\C--Users-PC-Desktop-hotspot-billing\memory\
```

That is the index plus eleven notes: the local dev traps, the payment rail harness,
the security posture, the competitive roadmap, the working-style preferences. Losing
it means re-learning things like why M-Pesa always fails locally and why phone
testing 403s.

**The folder name encodes the absolute path of the project.** `C--Users-PC-Desktop-hotspot-billing`
is `C:\Users\PC\Desktop\hotspot-billing` with the separators flattened. Put the repo
somewhere else on the new machine and the folder has to be renamed to match, or
Claude Code will not find any of it and will start from nothing.

Also worth copying, both optional:

- `C:\Users\PC\.claude\skills\` — 232 files, 8.5 MB of user-level skills.
- `.claude/settings.local.json` — the permission allowlist. Gitignored and it
  regenerates, but copying it saves re-approving several hundred commands.

## Things that will bite you on day one

- **`mvnw` picks the wrong JDK.** If `JAVA_HOME` points at an old JDK, the build
  fails parsing records and switch expressions in files nobody has touched. Set it
  explicitly in the same shell.
- **Testing on a phone over the LAN 403s every POST.** CORS defaults to
  `localhost` only. Start the backend with
  `APP_CORS_ALLOWED_ORIGIN_PATTERNS='http://localhost:*,http://127.0.0.1:*,http://<lan-ip>:*'`.
  Note `curl` sends no `Origin` header, so curl tests pass while the browser fails.
- **Passkeys cannot work over a LAN IP.** WebAuthn needs HTTPS or `localhost`, and
  the `admin` account has one enrolled. Use the password, or tunnel.
- **`mvnw package` bakes whatever is in `static/` at that moment.** On a fresh clone
  that is nothing. Always build the frontend first.
- **Windows locks the jar while the app is running.** Kill the process on 8081
  before packaging, or you get a 4.5 MB thin jar instead of a 68 MB one.

There is a `run-app` skill in the repo that automates the happy path once the
prerequisites are in place.
