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

if [ -f "$REPO/local.properties" ]; then
    cp "$REPO/local.properties" "$OUT/"
    echo "  local.properties      copied  (LIVE DARAJA KEYS -- handle accordingly)"
else
    echo "  local.properties      absent  (M-Pesa will not push; app still runs)"
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
echo
echo "Done. $(du -sh "$OUT" | cut -f1) at $OUT"
echo
echo "This bundle holds live Daraja credentials. Move it directly, then delete it."
