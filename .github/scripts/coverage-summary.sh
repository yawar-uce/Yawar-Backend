#!/usr/bin/env bash
# Prints the JaCoCo line coverage of every module as a Markdown table (V-01, threshold 70 %).
# Usage: .github/scripts/coverage-summary.sh [>> "$GITHUB_STEP_SUMMARY"]
# Reads <module>/target/site/jacoco/jacoco.csv (columns 8 = LINE_MISSED, 9 = LINE_COVERED).
set -euo pipefail
shopt -s nullglob

csvs=(*/target/site/jacoco/jacoco.csv)
echo "### Coverage (JaCoCo, line, threshold 70 %)"
echo
if [ ${#csvs[@]} -eq 0 ]; then
  echo "No coverage reports found."
  exit 0
fi
echo "| Module | Line coverage | Lines covered / total |"
echo "|---|---|---|"
for csv in "${csvs[@]}"; do
  module=${csv%%/*}
  awk -F, -v m="$module" 'NR > 1 { miss += $8; cov += $9 }
    END { total = miss + cov; pct = total ? 100 * cov / total : 0;
          printf "| %s | %.1f %% %s | %d / %d |\n", m, pct, (pct >= 70 ? "✅" : "❌"), cov, total }' "$csv"
done
