#!/usr/bin/env bash
# Publish Chips Casino to Modrinth.
#
# This project ships seven jars across two Minecraft lines, so unlike a single-loader
# project the publication is a small matrix: each jar declares the game versions and
# loaders it actually supports, and Modrinth will only offer it to matching instances.
#
# Required environment:
#   MODRINTH_TOKEN   - a Modrinth personal access token (Settings -> PATs)
#
# Optional:
#   VERSION          - version number (default: read from gradle.properties)
#   PROJECT_ID       - Modrinth project id/slug (default: chips-casino)
#   CHANGELOG        - changelog file (default: CHANGELOG.md if present)
#   RELEASE_TYPE     - release | beta | alpha (default: beta)
#   ONLY             - publish a single jar by key (see below), for resuming a failure
#
# Usage:
#   export MODRINTH_TOKEN=mrp_...
#   tools/publish-modrinth.sh            # publish every jar
#   ONLY=mc26.3 tools/publish-modrinth.sh   # publish just the 26.3 Fabric build
#
# Creating the project the first time: this script publishes *versions* to an existing
# project, because Modrinth has no API for creating a project (that is a web form). Create
# the project once at https://modrinth.com/projects/create with a slug matching PROJECT_ID,
# then run this.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-$(grep -hE '^version=' mc26.3/gradle.properties 2>/dev/null | head -1 | cut -d= -f2 | tr -d '[:space:]')}"
VERSION="${VERSION:-0.1.0}"
PROJECT_ID="${PROJECT_ID:-chips-casino}"
RELEASE_TYPE="${RELEASE_TYPE:-beta}"
ONLY="${ONLY:-}"

if [[ -z "${MODRINTH_TOKEN:-}" ]]; then
  # Fall back to the local token file if the environment does not carry it.
  if [[ -f "$HOME/.config/maemi/modrinth-token" ]]; then
    MODRINTH_TOKEN="$(tr -d '[:space:]' < "$HOME/.config/maemi/modrinth-token")"
  fi
fi

if [[ -z "${MODRINTH_TOKEN:-}" ]]; then
  echo "ERROR: MODRINTH_TOKEN is not set." >&2
  echo "Create a token at https://modrinth.com/settings/pats and export it." >&2
  exit 1
fi

API="https://api.modrinth.com/v2"

# ---------------------------------------------------------------------------
# The publication matrix. Key -> jar path | game versions | loaders
# ---------------------------------------------------------------------------
declare -A JARS GAME_VERSIONS LOADERS

JARS[mc26.3]="mc26.3/build/libs/casino-${VERSION}.jar"
GAME_VERSIONS[mc26.3]="26.3"
LOADERS[mc26.3]="fabric"

JARS[mc1.20.1-fabric]="mc1.20.1/build/libs/casino-${VERSION}.jar"
GAME_VERSIONS[mc1.20.1-fabric]="1.20.1"
LOADERS[mc1.20.1-fabric]="fabric"

JARS[mc1.20.1-forge]="mc1.20.1-forge/build/libs/casino-${VERSION}.jar"
GAME_VERSIONS[mc1.20.1-forge]="1.20.1"
LOADERS[mc1.20.1-forge]="forge"

JARS[mc1.20.1-neoforge]="mc1.20.1-neoforge/build/libs/casino-${VERSION}.jar"
GAME_VERSIONS[mc1.20.1-neoforge]="1.20.1"
LOADERS[mc1.20.1-neoforge]="neoforge"

# The Architectury build produces one jar per loader from the same common sources.
for loader in fabric forge neoforge; do
  key="mc1.20.1-arch-${loader}"
  JARS[$key]="mc1.20.1-arch/${loader}/build/libs/casino-${VERSION}.jar"
  GAME_VERSIONS[$key]="1.20.1"
  LOADERS[$key]="$loader"
done

ORDER=(mc26.3 mc1.20.1-fabric mc1.20.1-forge mc1.20.1-neoforge
       mc1.20.1-arch-fabric mc1.20.1-arch-forge mc1.20.1-arch-neoforge)

# ---------------------------------------------------------------------------
# Preflight: every jar must exist and pass the content verifier before upload.
# ---------------------------------------------------------------------------
missing=0
for key in "${ORDER[@]}"; do
  [[ -n "$ONLY" && "$key" != "$ONLY" ]] && continue
  if [[ ! -f "${JARS[$key]}" ]]; then
    echo "ERROR: ${JARS[$key]} not found. Run: mc_gradle <tree> build" >&2
    missing=1
  fi
done
if [[ "$missing" -ne 0 ]]; then
  exit 1
fi

echo "==> verifying jars before upload"
if [[ -x tools/verify_jars.py ]] || [[ -f tools/verify_jars.py ]]; then
  python3 tools/verify_jars.py > /tmp/casino-verify.log 2>&1 || {
    echo "ERROR: jar verification failed; refusing to publish." >&2
    tail -20 /tmp/casino-verify.log >&2
    exit 1
  }
  tail -2 /tmp/casino-verify.log
fi

CHANGELOG_FILE="${CHANGELOG:-}"
if [[ -z "$CHANGELOG_FILE" && -f CHANGELOG.md ]]; then
  CHANGELOG_FILE="CHANGELOG.md"
fi

echo "==> publishing version ${VERSION} to project '${PROJECT_ID}' (channel: ${RELEASE_TYPE})"

failures=0
for key in "${ORDER[@]}"; do
  [[ -n "$ONLY" && "$key" != "$ONLY" ]] && continue

  jar="${JARS[$key]}"
  gv="${GAME_VERSIONS[$key]}"
  ld="${LOADERS[$key]}"

  echo ""
  echo "--- ${key}: ${jar} (MC ${gv}, ${ld}) ---"

  # A version name must be unique per project, so it is derived from the jar's own
  # targets rather than the bare version number.
  VNAME="${VERSION}+${ld}-mc${gv}"

  BODY=$(python3 - "$PROJECT_ID" "$VNAME" "$CHANGELOG_FILE" "$RELEASE_TYPE" "$gv" "$ld" \
                  "$(basename "$jar")" <<'PY'
import json, sys, pathlib
project, vname, changelog_file, release_type, gv, ld, fname = sys.argv[1:8]
changelog = ""
if changelog_file and pathlib.Path(changelog_file).is_file():
    changelog = pathlib.Path(changelog_file).read_text(encoding="utf-8")
body = {
    "name": f"Chips Casino {vname}",
    "version_number": vname,
    "changelog": changelog,
    "dependencies": [],
    "game_versions": [gv],
    "loaders": [ld],
    "release_channel": release_type,
    "featured": True,
    "project_id": project,
    # The v2 version endpoint names this list `file_parts` (not `files`), and the
    # uploaded part is matched by the filename declared here. Modrinth hashes the part
    # itself, so no hash map is sent.
    "file_parts": [fname],
    "primary_file": fname,
}
print(json.dumps(body))
PY
)

  # The JSON must be sent as a file part, not an inline -F value: an inline part has its
  # newlines and quoting mangled by multipart encoding, and Modrinth then fails to parse it
  # with an EOF error partway through the body.
  payload_file=$(mktemp)
  printf '%s' "$BODY" > "$payload_file"

  response=$(curl -sS -X POST "${API}/version" \
    -H "Authorization: Bearer ${MODRINTH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -F "data=@${payload_file};type=application/json" \
    -F "file=@${jar};type=application/java-archive" \
    -w "\n%{http_code}")

  rm -f "$payload_file"

  code=$(printf '%s' "$response" | tail -1)
  payload=$(printf '%s' "$response" | sed '$d')

  if [[ "$code" == "200" || "$code" == "201" ]]; then
    vid=$(printf '%s' "$payload" | python3 -c "import sys,json;print(json.load(sys.stdin).get('id','?'))")
    echo "    OK  version id: ${vid}"
  else
    echo "    FAILED (HTTP ${code})" >&2
    printf '%s\n' "$payload" | head -c 800 >&2
    echo >&2
    failures=$((failures + 1))
  fi
done

echo ""
if [[ "$failures" -eq 0 ]]; then
  echo "==> done: all versions published"
  echo "    https://modrinth.com/mod/${PROJECT_ID}/versions"
else
  echo "==> ${failures} upload(s) failed" >&2
  exit 1
fi
