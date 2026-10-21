#!/usr/bin/env bash
# The single quality gate. Exits non-zero if any check fails.
# Auto-detects the stack. To take full control, create scripts/check.local.sh
# (executable); it replaces everything below.

set -u
cd "$(dirname "$0")/.." || exit 1

if [ -x scripts/check.local.sh ]; then
  exec scripts/check.local.sh
fi

fail=0
ran=0
run() {
  echo "==> $*"
  ran=$((ran + 1))
  if ! "$@"; then
    echo "FAILED: $*"
    fail=1
  fi
}

# Gradle / Android: `check` runs unit tests and lint.
if [ -x ./gradlew ]; then
  run ./gradlew check --console=plain
fi

# Node
if [ -f package.json ] && command -v npm >/dev/null 2>&1; then
  for s in typecheck lint test; do
    if command -v jq >/dev/null 2>&1 && jq -e --arg s "$s" '.scripts[$s]' package.json >/dev/null 2>&1; then
      run npm run --silent "$s"
    fi
  done
fi

# Python
if [ -f pyproject.toml ] || [ -f pytest.ini ] || [ -f setup.cfg ] || [ -d tests ]; then
  if command -v pytest >/dev/null 2>&1; then run pytest -q; fi
  if command -v ruff >/dev/null 2>&1 && { [ -f ruff.toml ] || grep -qs '\[tool.ruff' pyproject.toml; }; then run ruff check .; fi
  if command -v mypy >/dev/null 2>&1 && { [ -f mypy.ini ] || grep -qs '\[tool.mypy' pyproject.toml; }; then run mypy .; fi
fi

# Rust
if [ -f Cargo.toml ] && command -v cargo >/dev/null 2>&1; then
  run cargo check --quiet
  run cargo test --quiet
  if cargo clippy --version >/dev/null 2>&1; then run cargo clippy --quiet -- -D warnings; fi
fi

# Go
if [ -f go.mod ] && command -v go >/dev/null 2>&1; then
  run go vet ./...
  run go test ./...
fi

if [ "$ran" -eq 0 ]; then
  echo "WARNING: no checks were detected for this project, so this gate proves nothing."
  echo "Create scripts/check.local.sh with your real build/lint/test commands (milestone M0)."
  exit 0
fi

if [ "$fail" -ne 0 ]; then
  echo "CHECK FAILED"
  exit 1
fi
echo "CHECK PASSED ($ran checks)"
