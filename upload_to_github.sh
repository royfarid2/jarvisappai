#!/usr/bin/env bash
# Uploads this project to your GitHub repository (main branch).
# Run from anywhere:  bash upload_to_github.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

say()  { printf '\n\033[1;36m==>\033[0m %s\n' "$*"; }
fail() { printf '\n\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

command -v git >/dev/null 2>&1 || fail "git is not installed. Install it from https://git-scm.com and re-run."

say "Checking the GitHub Actions workflow"
WF=".github/workflows/android.yml"
[ -f "$WF" ] || fail "$WF is missing."
grep -q "assembleDebug" "$WF"   || fail "$WF does not build the debug APK (assembleDebug not found)."
grep -q "upload-artifact" "$WF" || fail "$WF does not upload the APK artifact."
echo "OK: $WF builds the debug APK and uploads it as an artifact."

say "Scanning for accidentally included secrets"
if grep -rIEn --exclude-dir=.git --exclude=upload_to_github.sh \
     -e 'sk-[A-Za-z0-9_-]{20,}' -e 'AIza[0-9A-Za-z_-]{35}' -e 'ghp_[A-Za-z0-9]{30,}' \
     -e 'github_pat_[A-Za-z0-9_]{30,}' -e 'xox[baprs]-[A-Za-z0-9-]{10,}' -e 'BEGIN (RSA |EC )?PRIVATE KEY' . ; then
  fail "Possible secret found above. Remove it and run this script again."
fi
echo "OK: no secrets detected."

say "Initializing Git"
if [ ! -d .git ]; then git init; fi

if [ -z "$(git config user.name || true)" ];  then read -rp "Git user.name  (for commits): " N; git config user.name  "$N"; fi
if [ -z "$(git config user.email || true)" ]; then read -rp "Git user.email (for commits): " E; git config user.email "$E"; fi

echo
echo "Create an EMPTY repository named 'jarvisappai' on GitHub first (no README, no .gitignore)."
read -rp "Paste your GitHub repository URL (e.g. https://github.com/YOU/jarvisappai.git): " REPO_URL
REPO_URL="${REPO_URL//[[:space:]]/}"
[ -n "$REPO_URL" ] || fail "No repository URL given."
case "$REPO_URL" in
  https://*|http://*|git@*|ssh://*) ;;
  *) fail "That doesn't look like a Git URL." ;;
esac

say "Committing project files"
git add -A
if git diff --cached --quiet; then
  echo "Nothing new to commit."
else
  git commit -m "Initial commit: JARVIS AI Android assistant"
fi
git branch -M main

say "Connecting to GitHub"
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REPO_URL"
else
  git remote add origin "$REPO_URL"
fi

say "Pushing to main"
echo "(If asked for a password over HTTPS, use a GitHub Personal Access Token, not your account password.)"
if ! git push -u origin main; then
  fail "Push failed. Make sure the repository is empty, the URL is correct, and you are signed in (token or SSH key)."
fi

say "Done! Next steps"
echo "1. Open your repo on GitHub -> Actions tab (enable workflows if prompted)."
echo "2. Open the latest 'Android CI' run -> Artifacts -> download 'jarvis-ai-debug-apk'."
echo "3. Unzip it and install app-debug.apk on your phone."
