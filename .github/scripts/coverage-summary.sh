#!/usr/bin/env bash
# Prints the JaCoCo line coverage of every module as a Markdown table (V-01, threshold 70 %).
# Usage: .github/scripts/coverage-summary.sh [>> "$GITHUB_STEP_SUMMARY"]
# Reads <module>/target/site/jacoco/jacoco.csv (columns 8 = LINE_MISSED, 9 = LINE_COVERED).
set -euo pipefail
shopt -s nullglob

# Only modules of this checkout (a folder with a pom.xml), never leftovers of other branches.
csvs=()
for csv in */target/site/jacoco/jacoco.csv; do
  [ -f "${csv%%/*}/pom.xml" ] && csvs+=("$csv")
done
echo "### Coverage (JaCoCo, line, threshold 70 %)"
echo
if [ ${#csvs[@]} -eq 0 ]; then
  echo "No coverage reports found."
  exit 0
fi
modules=$(for pom in */pom.xml; do dirname "$pom"; done | wc -l)
echo "_${#csvs[@]} of ${modules} modules produced a coverage report._"
echo
echo "| Module | Line coverage | Lines covered / total |"
echo "|---|---|---|"
for csv in "${csvs[@]}"; do
  module=${csv%%/*}
  awk -F, -v m="$module" 'NR > 1 { miss += $8; cov += $9 }
    END { total = miss + cov; pct = total ? 100 * cov / total : 0;
          printf "| %s | %.1f %% %s | %d / %d |\n", m, pct, (pct >= 70 ? "✅" : "❌"), cov, total }' "$csv"
done
