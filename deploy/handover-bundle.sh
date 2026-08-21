#!/usr/bin/env bash
#
# Collect the three things git does not carry, for moving to another machine.
#
# Everything else in this project is either in the repo or rebuilt by npm and
# maven. This script exists for the parts that are neither: the M-Pesa
# credentials, the operator's uploaded branding, and the database.
#
# It also copies Claude Code's project memory, which lives outside the repo and
# has no other way of travelling.
#
# The output contains live credentials. Move it on a USB stick or through a
# password manager, delete it afterwards, and do not commit it or send it
# through anything that keeps a copy.
#
# Usage:  bash deploy/handover-bundle.sh [output-dir]

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$HOME/Desktop/zidi-handover}"
DB_NAME="${DB_NAME:-hotspot_billing}"
DB_USER="${DB_USERNAME:-postgres}"

echo "Bundling from $REPO"
echo "           to $OUT"
echo

mkdir -p "$OUT"

# --- 1. Credentials -------------------------------------------------------

# Deliberately NOT copied. The Daraja keys can be read again from the portal in
# about the time it takes to find a USB stick, and a credential that never
# leaves the machine cannot be intercepted, forgotten on a shared drive, or left
# sitting in a bundle somebody meant to delete. Moving a secret you can simply
# re-fetch is a risk taken for no benefit.
#
# Set INCLUDE_SECRETS=1 to override, if you have a reason.

if [ "${INCLUDE_SECRETS:-0}" = "1" ] && [ -f "$REPO/local.properties" ]; then
    cp "$REPO/local.properties" "$OUT/"
    echo "  local.properties      COPIED -- live keys, delete this bundle after use"
elif [ -f "$REPO/local.properties" ]; then
    KEYS="$(grep -oE '^[a-zA-Z0-9._-]+' "$REPO/local.properties" | sort -u)"
    {
        echo "Re-create local.properties on the new machine"
        echo "============================================="
        echo
        echo "Not included in this bundle on purpose: these are live credentials and"
        echo "they can be read again from the source in a couple of minutes, so there"
        echo "is no reason to put them on a network or a shared drive."
        echo
        echo "Create local.properties in the repo root with these keys:"
        echo
        echo "$KEYS" | sed 's/^/    /'
        echo
        echo "Where each comes from:"
        echo
        echo "  consumer-key, consumer-secret"
        echo "      developer.safaricom.co.ke -> your app -> Keys. Both are shown"
        echo "      there; the secret can be regenerated if you would rather rotate"
        echo "      it while moving."
        echo
        echo "  passkey"
        echo "      Same portal, the sandbox test credentials page. It is per-"
        echo "      shortcode, not per-app."
        echo
        echo "  callback-url"
        echo "      A fresh ngrok URL. The old one is dead regardless -- ngrok issues"
        echo "      a new hostname every restart, so this needed re-pasting anyway."
        echo
        echo "  callback-allowed-ips"
        echo "      Safaricom's published callback ranges. Copy the line from the old"
        echo "      machine if you still have it; it is not secret."
        echo
        echo "Until this file exists the app boots and runs fine. Vouchers redeem,"
        echo "the portal works, the admin works. Only live STK pushes fail."
    } > "$OUT/MPESA-CREDENTIALS.txt"
    echo "  local.properties      SKIPPED -- see MPESA-CREDENTIALS.txt (re-fetch, do not move)"
else
    echo "  local.properties      absent here"
fi

# --- 2. Operator branding -------------------------------------------------
#
# The database stores bare filenames in portal_settings.logo_filename, so these
# files and a restored database only make sense as a pair.

if [ -d "$REPO/uploads" ] && [ -n "$(ls -A "$REPO/uploads" 2>/dev/null)" ]; then
    mkdir -p "$OUT/uploads"
    cp -r "$REPO/uploads/." "$OUT/uploads/"
    echo "  uploads/              copied  ($(find "$REPO/uploads" -type f | wc -l | tr -d ' ') files)"
else
    echo "  uploads/              empty or absent"
fi

# --- 3. The database ------------------------------------------------------
#
# Custom format (-Fc) rather than plain SQL: it restores with pg_restore on a
# different PostgreSQL minor version without complaining, and it compresses.

if command -v pg_dump >/dev/null 2>&1; then
    if pg_dump -U "$DB_USER" -Fc "$DB_NAME" > "$OUT/$DB_NAME.dump" 2>"$OUT/.dump-err"; then
        echo "  $DB_NAME.dump  copied  ($(du -h "$OUT/$DB_NAME.dump" | cut -f1))"
        rm -f "$OUT/.dump-err"
    else
        echo "  DATABASE DUMP FAILED:"
        sed 's/^/      /' "$OUT/.dump-err"
        echo "      (set PGPASSWORD, or start clean and let Flyway build the schema)"
    fi
else
    echo "  pg_dump not on PATH -- skipping the database"
fi

# --- 4. Claude Code project memory ----------------------------------------
#
# Lives under a directory named after the project's absolute path, which is why
# the note below matters: move the repo elsewhere and the name must change with
# it or none of this is found.

# Derived from the project's absolute path in WINDOWS form, which is not the
# form the shell reports. Git Bash says /c/Users/..., Claude Code names the
# folder from C:\Users\... -- so the first version of this line produced a slug
# that matched nothing and skipped the memory without failing. `pwd -W` gives
# the Windows path where it exists, and the drive colon becomes the second dash
# in "C--Users-...".
REPO_WIN="$(cd "$REPO" && { pwd -W 2>/dev/null || pwd; })"
MEM_SLUG="$(echo "$REPO_WIN" | sed 's#^/\([A-Za-z]\)/#\1:/#' | sed 's#:#-#; s#[/\\]#-#g')"
MEM_SRC="$HOME/.claude/projects/$MEM_SLUG/memory"

if [ -d "$MEM_SRC" ]; then
    mkdir -p "$OUT/claude-memory"
    cp -r "$MEM_SRC/." "$OUT/claude-memory/"
    echo "  claude-memory/        copied  ($(find "$MEM_SRC" -type f | wc -l | tr -d ' ') files)"
    echo "$MEM_SLUG" > "$OUT/claude-memory/.original-project-slug"
else
    echo "  claude memory         not found at $MEM_SRC"
fi

# --- What to do with it ---------------------------------------------------

cat > "$OUT/RESTORE.txt" <<EOF
Restoring on the new machine
============================

Read deploy/NEW-DEVICE.md in the repo first -- it covers the build steps, and
the frontend one is not optional.

1. Clone, create the database, build the frontend:

     git clone https://github.com/kelvinkiumbe1/hotspot-billing.git
     cd hotspot-billing
     createdb -U postgres $DB_NAME
     cd frontend && npm install && npm run build && cd ..
     rm -rf src/main/resources/static
     cp -r frontend/dist src/main/resources/static

2. Drop these back in:

     cp local.properties  <repo>/
     cp -r uploads        <repo>/

3. Database -- only if you want the existing data. Skipping this is fine;
   Flyway builds all 105 tables from the 86 migrations in the repo.

     pg_restore -U postgres -d $DB_NAME $DB_NAME.dump

   Restore the database and uploads/ together or neither. The database stores
   bare filenames, so one without the other is a broken logo.

4. Claude Code memory. The destination folder is named after the repo's
   absolute path on the NEW machine, which is probably not the old one:

     old: $MEM_SLUG

   Same path       -> ~/.claude/projects/$MEM_SLUG/memory/
   Different path  -> flatten the new absolute path the same way. A repo at
                      C:\\dev\\hotspot-billing becomes C--dev-hotspot-billing.

     mkdir -p ~/.claude/projects/<slug>/memory
     cp -r claude-memory/. ~/.claude/projects/<slug>/memory/

   Get the slug wrong and nothing breaks -- Claude Code just starts with no
   memory of this project, which is easy to miss.

5. Start it:

     export JAVA_HOME=/path/to/jdk21+
     ./mvnw spring-boot:run          # http://localhost:8081

6. Then delete this bundle. It contains live payment credentials.
EOF

echo
echo "  RESTORE.txt           written"

# --- 5. One encrypted file, for moving it without a USB stick -------------
#
# The database dump is the reason this is encrypted rather than just tarred.
# It carries real customer phone numbers -- personal data under Kenya's Data
# Protection Act, not test rows -- so it should not sit unprotected on a shared
# drive, in a cloud account's trash, or on an unauthenticated LAN listener.
#
# AES-256 with PBKDF2 and a high iteration count, because the password will be
# something a human types once. Plain `openssl enc` without -pbkdf2 uses a
# single MD5 pass for key derivation, which is not worth relying on.

if command -v openssl >/dev/null 2>&1; then
    ARCHIVE="$OUT.tar.gz.enc"
    echo
    echo "Encrypting the bundle into one file."
    echo "Choose a passphrase you can type on the other machine, and send it by a"
    echo "different route than the file itself -- not in the same message."
    echo

    # The passphrase is read here and handed over as an environment variable
    # rather than left to `openssl enc`'s own prompt. That prompt reads the
    # terminal directly, not stdin, so it hangs forever the moment this script
    # runs anywhere without a tty -- piped, backgrounded, or from a task runner
    # -- with no output to say why. `-pass env:` also keeps it out of argv,
    # where `ps` would show it.
    if [ -n "${BUNDLE_PASS:-}" ]; then
        echo "  using BUNDLE_PASS from the environment"
    elif [ -t 0 ]; then
        read -rsp "  Passphrase: " BUNDLE_PASS; echo
        read -rsp "  Again:      " CONFIRM; echo
        if [ "$BUNDLE_PASS" != "$CONFIRM" ]; then
            echo "  passphrases differ -- stopping before writing a file you cannot open"
            exit 1
        fi
        if [ ${#BUNDLE_PASS} -lt 12 ]; then
            echo "  too short; use at least 12 characters"
            exit 1
        fi
    else
        echo "  no tty and no BUNDLE_PASS set -- skipping encryption."
        echo "  Re-run with: BUNDLE_PASS='...' bash deploy/handover-bundle.sh"
        echo
        echo "Done. $(du -sh "$OUT" | cut -f1) at $OUT"
        exit 0
    fi
    export BUNDLE_PASS

    if tar -czf - -C "$(dirname "$OUT")" "$(basename "$OUT")" \
        | openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
            -pass env:BUNDLE_PASS -out "$ARCHIVE"; then
        echo
        echo "  $(basename "$ARCHIVE")  ($(du -h "$ARCHIVE" | cut -f1))"
        echo
        echo "To move it over the local network, serve it from here:"
        echo
        echo "    cd \"$(dirname "$OUT")\" && python -m http.server 8000"
        echo
        for ip in $(command -v powershell >/dev/null 2>&1 && powershell -NoProfile -Command \
            "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { \$_.IPAddress -notlike '127.*' -and \$_.IPAddress -notlike '169.254.*' -and \$_.InterfaceAlias -notlike '*WSL*' } | ForEach-Object { \$_.IPAddress }" 2>/dev/null | tr -d '\r'); do
            echo "  then on the new machine, on the same network:"
            echo
            echo "    curl -O http://$ip:8000/$(basename "$ARCHIVE")"
        done
        echo
        echo "  and unpack it there:"
        echo
        echo "    openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 \\"
        echo "      -in $(basename "$ARCHIVE") | tar -xzf -"
        echo
        echo "Stop the server as soon as the download finishes -- it is"
        echo "unauthenticated, and anything else on that network can reach it."
    else
        echo "  encryption failed; the plain folder is still at $OUT"
    fi
fi

echo
echo "Done. $(du -sh "$OUT" | cut -f1) at $OUT"
