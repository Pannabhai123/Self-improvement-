#!/usr/bin/env bash
# ------------------------------------------------------------
#  Runs a Gradle task in CI and, on failure, publishes the
#  compiler / resource / test diagnostics as a GitHub Actions
#  annotation.
#
#  Why this exists: the run-log host is not reachable from every
#  environment that maintains this repository, and an annotation
#  is served from api.github.com. Without this, a build failure
#  reads only as "Process completed with exit code 1" and the
#  actual javac or aapt2 error is invisible.
# ------------------------------------------------------------
set -uo pipefail

GRADLE="${FL_GRADLE:-gradle}"
NAME="$1"; shift
LOG="/tmp/ci-${NAME}.log"

echo "::group::${NAME}: ${GRADLE} $*"
"$GRADLE" "$@" --stacktrace > "$LOG" 2>&1
rc=$?
echo "::endgroup::"

# Always show the tail: it is free, and it is what a human reading
# the run page needs.
tail -n 80 "$LOG"

if [ "$rc" -ne 0 ]; then
  # Only lines that name something to fix. Warnings are deliberately excluded:
  # Room prints a benign "multiple good constructors" note for every entity, and
  # twenty of those pushed the real failure out of the annotation entirely.
  # Gradle's own stack trace is excluded for the same reason - it is long, names
  # no source file, and would consume the budget before any javac error appeared.
  # -A 2 matters: javac puts the reason on the line AFTER the error, e.g.
  #   error: getAccessibilityClassName() cannot override ...
  #     attempting to assign weaker access privileges; was public
  # and Gradle puts the expected/actual of a failed assertion there too.
  errors="$(grep -A 2 -E \
    'error:|> Task .*FAILED|FAILED$|Could not resolve|Could not find|Unresolved reference|There were failing tests|AssertionError|ComparisonFailure|AAPT:' \
    "$LOG" | grep -vE '^[[:space:]]*at |warning:|^[0-9]+ error|^--$' | head -n 200)"

  # Then the short Gradle summary, which names the failing task and its cause.
  summary="$(grep -E 'What went wrong:|Execution failed for task|A problem occurred' \
    "$LOG" | head -n 8)"

  detail="$errors"
  [ -n "$summary" ] && detail="${errors:+$errors
}$summary"

  if [ -z "$detail" ]; then
    detail="$(tail -n 50 "$LOG")"
  fi

  # Annotation messages must be single-line; GitHub unescapes %0A back
  # into a newline and %25 back into a percent. Percent has to be
  # escaped first or the other substitutions get double-escaped.
  encoded="$(printf '%s' "$detail" | python3 -c '
import sys
s = sys.stdin.read()
s = s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
# Annotations are truncated by the UI; keep it inside a useful budget.
print(s[:60000], end="")
')"

  echo "::error title=${NAME} failed (exit ${rc})::${encoded}"
fi

exit "$rc"
