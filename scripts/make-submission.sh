#!/usr/bin/env bash
#
# make-submission.sh — regenerate the test-free `submission` branch.
#
# The RuneLite Plugin Hub builds (and reviewers read) the exact git commit you
# point it at. This script derives a clean, production commit from the dev tree
# that matches the standard RuneLite external-plugin layout you have always
# submitted, by removing only the dev-only additions layered on top of it.
#
# What is stripped:
#
#   - the unit-test suite under src/test/  (every *Test.java that has @Test),
#     BUT the standard dev launcher src/test/.../RuneAlyticsPluginTest.java is
#     KEPT — it is part of the RuneLite template, has no @Test methods, and is
#     the Main-Class that the shadowJar task boots.
#   - src/test test resources (e.g. logback-test.xml) added for the suite.
#   - checkstyle.xml            (dev-only quality gate)
#   - .github/                  (our development CI workflows)
#   - scripts/                  (this tooling — never needed on submission)
#   - docs/                     (dev-only reference docs, e.g. backend notes)
#
# It also trims from build.gradle ONLY the dev-only additions:
#   - the `id 'checkstyle'` plugin line, the `checkstyle { ... }` block, and the
#     standalone `configurations.checkstyle { ... }` exclude line, and
#   - the `testImplementation` lines for mockito and mockwebserver.
#
# Everything that has always shipped is preserved verbatim: the shadowJar task
# (and its RuneAlyticsPluginTest Main-Class), and the junit / runelite client /
# jshell testImplementation dependencies. The dev tree (master) keeps its full
# build.gradle untouched — the edit happens only in the throwaway worktree.
#
# The result is committed onto the `submission` branch, compiled to prove the
# stripped tree still builds on its own, and the resulting commit SHA is printed
# for you to paste into the plugin-hub PR.
#
# The dev tree (master) is never modified: all work happens in a throwaway git
# worktree. Re-running simply regenerates `submission` from the current HEAD, so
# the script is safe to run repeatedly.
#
# Usage:
#   scripts/make-submission.sh [SOURCE_REF]
#
#   SOURCE_REF        branch/commit to derive from (default: current HEAD)
#
# Environment:
#   SUBMISSION_BRANCH branch name to (re)create (default: submission)
#   SKIP_BUILD=1      skip the compile-verification step (e.g. no JDK locally)

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

SOURCE_REF="${1:-$(git rev-parse --abbrev-ref HEAD)}"
SUBMISSION_BRANCH="${SUBMISSION_BRANCH:-submission}"

# The standard dev launcher that MUST survive the strip (Main-Class of shadowJar).
KEEP_LAUNCHER="src/test/java/com/runealytics/RuneAlyticsPluginTest.java"

# Whole paths removed from the submission tree (dev-only tooling/config).
STRIP_PATHS=(
    "checkstyle.xml"
    ".github"
    "scripts"
    "docs"
)

info()  { printf '\033[36m[make-submission]\033[0m %s\n' "$*"; }
fail()  { printf '\033[31m[make-submission] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Remove dev-only declarations from build.gradle in the current directory:
#   * the `id 'checkstyle'` plugin line,
#   * the whole `checkstyle { ... }` block,
#   * the standalone `configurations.checkstyle { ... }` exclude line, and
#   * the mockito and mockwebserver testImplementation lines.
# The junit / runelite client / jshell testImplementation lines and the entire
# shadowJar task are left intact. The checkstyle block is matched by net-brace
# counting; a final pass trims trailing blank lines. Groovy validity is proven
# by the compileJava verification step that follows.
trim_build_gradle() {
    awk '
        # Drop the checkstyle plugin id (inside the plugins { } block).
        /^[[:space:]]*id[[:space:]]+.checkstyle./ { next }
        # Drop the dev-only test dependencies (keep junit + runelite client/jshell).
        /^[[:space:]]*testImplementation.*mockito/       { next }
        /^[[:space:]]*testImplementation.*mockwebserver/ { next }
        # Drop the standalone one-line configurations.checkstyle { ... } exclude.
        /^[[:space:]]*configurations\.checkstyle[[:space:]]*\{/ { next }
        # Drop the whole checkstyle { ... } block by brace counting.
        /^[[:space:]]*checkstyle[[:space:]]*\{/ { inCs=1; depth=0; seenOpen=0 }
        inCs {
            o=gsub(/[{]/,""); c=gsub(/[}]/,""); depth += o - c
            if (o>0) seenOpen=1
            if (seenOpen && depth<=0) inCs=0
            next
        }
        { print }
    ' build.gradle \
        | cat -s \
        | awk 'NF{last=NR} {line[NR]=$0} END{for (i=1;i<=last;i++) print line[i]}' \
        > build.gradle.tmp
    mv build.gradle.tmp build.gradle
}

# 1. Refuse to run against a dirty tree so we can't accidentally bake in
#    uncommitted changes (or lose them).
if ! git diff --quiet || ! git diff --cached --quiet; then
    fail "Working tree has uncommitted changes. Commit or stash them first."
fi

SOURCE_SHA="$(git rev-parse --verify "${SOURCE_REF}^{commit}")" \
    || fail "Cannot resolve SOURCE_REF='${SOURCE_REF}'."
info "Deriving submission from ${SOURCE_REF} (${SOURCE_SHA:0:12})"

# 2. Clean up any stale worktree that still holds the submission branch, so the
#    branch can be reset below.
git worktree prune
EXISTING_WT="$(git worktree list --porcelain \
    | awk -v b="refs/heads/${SUBMISSION_BRANCH}" \
        '/^worktree /{wt=$2} $0=="branch "b{print wt}')"
if [ -n "${EXISTING_WT}" ]; then
    git worktree remove --force "${EXISTING_WT}"
fi

# 3. Reset (or create) the submission branch at the source commit.
git branch -f "${SUBMISSION_BRANCH}" "${SOURCE_SHA}"

WORKTREE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/runealytics-submission.XXXXXX")"
cleanup() {
    git worktree remove --force "${WORKTREE_DIR}" >/dev/null 2>&1 || true
    git worktree prune >/dev/null 2>&1 || true
}
trap cleanup EXIT

git worktree add --quiet --force "${WORKTREE_DIR}" "${SUBMISSION_BRANCH}"

# 4. Strip dev-only paths + the unit tests and commit the result.
(
    cd "${WORKTREE_DIR}"

    for path in "${STRIP_PATHS[@]}"; do
        if [ -e "${path}" ]; then
            git rm -r --quiet -- "${path}"
        fi
    done

    # Remove the unit-test suite (and its test resources) while keeping the
    # standard RuneAlyticsPluginTest launcher that shadowJar boots.
    while IFS= read -r f; do
        [ "${f}" = "${KEEP_LAUNCHER}" ] && continue
        git rm --quiet -- "${f}"
    done < <(git ls-files 'src/test')

    # Trim the dev-only declarations from build.gradle (checkstyle + mockito +
    # mockwebserver); keep shadowJar and the junit/client/jshell test deps.
    trim_build_gradle
    git add build.gradle

    if git diff --cached --quiet; then
        info "Nothing to strip — source is already submission-clean."
    else
        git commit --quiet -m "build: strip dev/test tooling for Plugin Hub submission

Auto-generated by scripts/make-submission.sh from ${SOURCE_REF} (${SOURCE_SHA:0:12}).
Do not commit onto this branch by hand — re-run the script instead."
    fi

    # 5. Prove the stripped tree still builds on its own.
    if [ "${SKIP_BUILD:-0}" = "1" ]; then
        info "SKIP_BUILD=1 — skipping compile verification."
    else
        info "Verifying the stripped tree compiles..."
        ./gradlew --no-daemon --console=plain compileJava >/dev/null \
            || fail "Stripped submission tree failed to compile."
    fi

    # 6. Sanity checks: the launcher survived, no unit tests leaked, and
    #    build.gradle no longer references any dev-only tooling.
    if [ ! -f "${KEEP_LAUNCHER}" ]; then
        fail "Dev launcher ${KEEP_LAUNCHER} was removed — strip is too aggressive."
    fi
    LEAKED_TESTS="$(git ls-files 'src/test' | grep -v "^${KEEP_LAUNCHER}$" || true)"
    if [ -n "${LEAKED_TESTS}" ]; then
        fail "Unit-test files still present in submission tree:"$'\n'"${LEAKED_TESTS}"
    fi
    if grep -qE 'mockito|mockwebserver|checkstyle' build.gradle; then
        fail "build.gradle still references dev-only tooling — trim failed."
    fi
)

SUBMISSION_SHA="$(git rev-parse "${SUBMISSION_BRANCH}")"

info "Submission branch '${SUBMISSION_BRANCH}' is ready."
info "Commit SHA (paste into the plugin-hub PR):"
echo "${SUBMISSION_SHA}"
