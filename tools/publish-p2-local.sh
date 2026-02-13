#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
P2_DIR="$ROOT_DIR/repositories/com.codepilot1c.update/target/repository"
WORKTREE_DIR="$ROOT_DIR/.tmp/gh-pages-worktree"
REMOTE_NAME="${REMOTE_NAME:-origin}"
BRANCH_NAME="${BRANCH_NAME:-gh-pages}"

cleanup() {
  if git -C "$ROOT_DIR" worktree list | grep -q "$WORKTREE_DIR"; then
    git -C "$ROOT_DIR" worktree remove --force "$WORKTREE_DIR" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

mkdir -p "$ROOT_DIR/.tmp"

echo "[1/5] Building update site locally..."
(
  cd "$ROOT_DIR"
  mvn -B -V --no-transfer-progress clean verify
)

if [[ ! -d "$P2_DIR" ]]; then
  echo "P2 repository directory not found: $P2_DIR" >&2
  exit 1
fi

echo "[2/5] Preparing landing pages..."
mkdir -p "$P2_DIR/site"
cp "$ROOT_DIR/site/index.html" "$P2_DIR/site/index.html"
cp "$ROOT_DIR/site/root-index.html" "$P2_DIR/index.html"

echo "[3/5] Preparing $BRANCH_NAME worktree..."
git -C "$ROOT_DIR" fetch "$REMOTE_NAME" "$BRANCH_NAME" >/dev/null 2>&1 || true

if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/remotes/$REMOTE_NAME/$BRANCH_NAME"; then
  git -C "$ROOT_DIR" worktree add --force -B "$BRANCH_NAME" "$WORKTREE_DIR" "$REMOTE_NAME/$BRANCH_NAME"
else
  git -C "$ROOT_DIR" worktree add --force --detach "$WORKTREE_DIR"
  (
    cd "$WORKTREE_DIR"
    git checkout --orphan "$BRANCH_NAME"
    git rm -rf . >/dev/null 2>&1 || true
  )
fi

echo "[4/5] Syncing p2 content into $BRANCH_NAME..."
rsync -a --delete "$P2_DIR/" "$WORKTREE_DIR/"

(
  cd "$WORKTREE_DIR"
  git add -A
  if git diff --cached --quiet; then
    echo "No changes to publish."
    exit 0
  fi
  git commit -m "Publish p2 from $(git -C "$ROOT_DIR" rev-parse --short HEAD)"
  echo "[5/5] Pushing to $REMOTE_NAME/$BRANCH_NAME..."
  git push "$REMOTE_NAME" "$BRANCH_NAME"
)

echo "Done. GitHub Pages branch updated: $REMOTE_NAME/$BRANCH_NAME"
