#!/usr/bin/env bash
# Refresh the READ-ONLY Windows reference snapshot (TRANSFER-MAP §0.3 rule 7).
# The Windows repo is never modified, committed to, or pushed to — this script only
# re-clones it into the local audit workspace, outside this Android repository.
set -euo pipefail
DEST="${1:-$HOME/workspace/interview-assistant-android/windows-reference}"
TMP="$(mktemp -d)"
git clone --depth 1 https://github.com/SaEeDX4/REAL-TIME-WINDOWS-INTERVIEW-ASSISTANT.git "$TMP/clone"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$TMP/clone/." "$DEST/"
rm -rf "$DEST/.git" "$TMP"
echo "Reference refreshed at $DEST — record the new commit in SYNC-LOG.md and re-run the §1.2 Lockstep protocol."
