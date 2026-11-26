#!/usr/bin/env bash
# Prints the JUnit XML results of every module as a Markdown table (tests, failures, errors, skipped).
# Usage: .github/scripts/test-summary.sh <surefire|failsafe> "<title>" [>> "$GITHUB_STEP_SUMMARY"]
# Reads <module>/target/<kind>-reports/TEST-*.xml (the first <testsuite> tag of each file).
set -euo pipefail
shopt -s nullglob

kind=${1:?surefire or failsafe}
title=${2:?title}

attr() { grep -m1 -o "<testsuite [^>]*>" "$1" | grep -o " $2=\"[0-9]*\"" | head -1 | tr -dc '0-9'; }

echo "### $title"
echo
rows=""
all_tests=0; all_failed=0
for dir in */target/"$kind"-reports; do
  [ -f "${dir%%/*}/pom.xml" ] || continue
  tests=0; failures=0; errors=0; skipped=0
  for xml in "$dir"/TEST-*.xml; do
    tests=$((tests + $(attr "$xml" tests))); failures=$((failures + $(attr "$xml" failures)))
    errors=$((errors + $(attr "$xml" errors))); skipped=$((skipped + $(attr "$xml" skipped)))
  done
  rows+="| ${dir%%/*} | $tests | $failures | $errors | $skipped |"$'\n'
  all_tests=$((all_tests + tests)); all_failed=$((all_failed + failures + errors))
done
if [ -z "$rows" ]; then
  echo "No test reports found."
  exit 0
fi
echo "**$all_tests tests, $all_failed failed**"
echo
echo "| Module | Tests | Failures | Errors | Skipped |"
echo "|---|---|---|---|---|"
printf '%s' "$rows"
