#!/usr/bin/env bash
#
# plugin-hub-guards.sh — fail the build on Plugin Hub kickback reasons we have
# already been hit with, so they never reach a plugin-hub PR again.
#
# Kickback #1: "Please remove all usages of java/lang/Runtime"
#   Plugin Hub's bytecode scanner looks for the CONSTANT_Utf8 class name
#   `java/lang/Runtime` in compiled .class files. RuntimeException is a
#   different class and is allowed. Source-level `Runtime.getRuntime()` /
#   `import java.lang.Runtime` also fail, because they compile to that name.
#
# Kickback #2: stray / "random" .class files
#   Plugin Hub reviews the git commit, not your local build/. Committed or
#   leftover .class files under src/ (or anywhere outside a build output dir)
#   get kicked back. This checker fails on:
#     - any git-tracked *.class
#     - any *.class sitting under src/
#     - any other *.class outside known build/IDE output directories
#
# Usage:
#   scripts/plugin-hub-guards.sh [--root DIR] [--classes DIR]
#
#   --root DIR      repository / worktree to inspect (default: cwd)
#   --classes DIR   compiled main classes to scan for java/lang/Runtime
#                   (default: <root>/build/classes/java/main)
#   SKIP_BYTECODE=1 skip the post-compile Runtime constant-pool scan
#
# Exit 0 when clean, 1 when any guard fails.

set -euo pipefail

ROOT="."
CLASSES=""
while [ $# -gt 0 ]; do
    case "$1" in
        --root)    ROOT="${2:?}";    shift 2 ;;
        --classes) CLASSES="${2:?}"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "${ROOT}" && pwd)"
CLASSES="${CLASSES:-${ROOT}/build/classes/java/main}"

info() { printf '\033[36m[plugin-hub-guards]\033[0m %s\n' "$*"; }
fail() { printf '\033[31m[plugin-hub-guards] ERROR:\033[0m %s\n' "$*" >&2; }

FAILED=0
fail_once() {
    fail "$1"
    FAILED=1
}

# ── Kickback #1: java/lang/Runtime ──────────────────────────────────────────

# Source scan: catch the API before it is compiled. Do not match
# RuntimeException / shownRuntime / testRuntimeClasspath.
check_runtime_in_sources() {
    local hits
    hits="$(
        find "${ROOT}/src/main/java" -name '*.java' -print0 2>/dev/null \
            | xargs -0 grep -nE \
                -e 'import[[:space:]]+java\.lang\.Runtime[[:space:]]*;' \
                -e '(^|[^A-Za-z0-9_])java\.lang\.Runtime([^A-Za-z0-9_]|$)' \
                -e '(^|[^A-Za-z0-9_])Runtime[[:space:]]*\.' \
                || true
    )"
    # xargs grep exit 1 = no matches; keep going.
    if [ -n "${hits}" ]; then
        fail_once "Plugin Hub kickback #1: usages of java.lang.Runtime in source.
Please remove all usages of java/lang/Runtime (RuntimeException is fine).
${hits}"
    fi
}

# Bytecode scan: matches Plugin Hub's class-name check. The constant-pool
# string "java/lang/RuntimeException" contains "java/lang/Runtime" as a prefix,
# so we require a non-identifier byte after Runtime.
check_runtime_in_bytecode() {
    if [ "${SKIP_BYTECODE:-0}" = "1" ]; then
        info "SKIP_BYTECODE=1 — skipping compiled-class Runtime scan."
        return
    fi
    if [ ! -d "${CLASSES}" ]; then
        fail_once "Compiled classes not found at ${CLASSES}.
Compile first (./gradlew compileJava) so the Runtime bytecode scan can run."
        return
    fi

    local hits
    hits="$(
        python3 - "${CLASSES}" <<'PY'
import re, sys
from pathlib import Path

root = Path(sys.argv[1])
# Complete class name only — not RuntimeException, not RuntimePermission.
needle = re.compile(rb"java/lang/Runtime(?![A-Za-z0-9_$/])")
bad = []
for path in root.rglob("*.class"):
    data = path.read_bytes()
    if needle.search(data):
        bad.append(str(path))
if bad:
    print("\n".join(sorted(bad)))
PY
    )"
    if [ -n "${hits}" ]; then
        fail_once "Plugin Hub kickback #1: compiled classes reference java/lang/Runtime.
Please remove all usages of java/lang/Runtime (RuntimeException is fine).
${hits}"
    fi
}

# ── Kickback #2: stray .class files ─────────────────────────────────────────

# Directories that are allowed to contain compiled output locally.
is_build_output() {
    case "$1" in
        */build/*|*/.gradle/*|*/out/*|*/.idea/*) return 0 ;;
        *) return 1 ;;
    esac
}

check_tracked_class_files() {
    local hits
    hits="$(
        git -C "${ROOT}" ls-files '*.class' || true
    )"
    if [ -n "${hits}" ]; then
        fail_once "Plugin Hub kickback #2: .class files are tracked in git.
Do not commit build artifacts. Remove them with: git rm -f --cached <file>
${hits}"
    fi
}

check_stray_class_files() {
    local hits=""
    local f rel
    # src/ must never contain compiled classes, even untracked.
    if [ -d "${ROOT}/src" ]; then
        while IFS= read -r f; do
            rel="${f#${ROOT}/}"
            hits="${hits}${rel}"$'\n'
        done < <(find "${ROOT}/src" -name '*.class' -type f 2>/dev/null || true)
    fi
    # Random .class files anywhere else except known build/IDE output.
    while IFS= read -r f; do
        rel="${f#${ROOT}/}"
        case "${rel}" in
            src/*) continue ;;
        esac
        if is_build_output "/${rel}"; then
            continue
        fi
        hits="${hits}${rel}"$'\n'
    done < <(find "${ROOT}" -name '*.class' -type f \
        -not -path "${ROOT}/build/*" \
        -not -path "${ROOT}/.gradle/*" \
        -not -path "${ROOT}/out/*" \
        -not -path "${ROOT}/.idea/*" \
        2>/dev/null || true)

    if [ -n "${hits}" ]; then
        fail_once "Plugin Hub kickback #2: stray .class files in the tree.
Plugin Hub reviews the git commit; leftover compiled classes under src/ or
the repo root get kicked back. Delete them — only .java sources belong here.
${hits}"
    fi
}

info "Checking ${ROOT}"
check_runtime_in_sources
check_runtime_in_bytecode
check_tracked_class_files
check_stray_class_files

if [ "${FAILED}" -ne 0 ]; then
    fail "Plugin Hub guards failed."
    exit 1
fi
info "Plugin Hub guards passed (no java/lang/Runtime, no stray .class files)."
