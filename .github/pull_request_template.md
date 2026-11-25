## 🎯 Summary
<2–4 sentences: what problem this solves and the outcome.>

## 🔗 Jira
- Task: [YAW-<n>](https://yawarblood26.atlassian.net/browse/YAW-<n>) — <task title>
- Subtasks: YAW-<a>, YAW-<b>

## 🧩 Type of change
<!-- Tick every type that applies with - [x] -->
- [ ] ✨ Feature
- [ ] 🐛 Fix
- [ ] 🧱 Infrastructure
- [ ] 👷 CI/CD
- [ ] 📝 Docs
- [ ] ✅ Tests
- [ ] ♻️ Refactor

## 🛠️ What was done
- <bullet per meaningful change, with file/folder paths>
- <design decisions and why>

## 🧪 How to test
> Copy-paste ready, from a clean clone on `dev`: `git fetch origin && git switch <branch>`.
> Write "N/A — <reason>" for a layer that does not apply.

**Unit** — one class in isolation (JUnit 5 + Mockito + AssertJ)
```bash
make test S=<service>
```
Expected: `Tests run: <n>, Failures: 0, Errors: 0`

**Integration** — code + real infrastructure (`@SpringBootTest` + Testcontainers, `@WebMvcTest` for 401/403)
```bash
make test-it S=<service>
```
Expected: `Tests run: <n>, Failures: 0, Errors: 0`

**Functional / acceptance** — one test per Gherkin scenario of the story, through the API (`huXX_scN_…`)
```bash
make acceptance S=<service>
```
Expected: <scenarios that pass, e.g. hu01_sc1 … hu01_sc4>

**Manual**
1. `<setup, e.g. cp deploy/local/.env.example deploy/local/.env && make up && make ps>`
2. `<action, e.g. curl -i -X POST http://localhost:8000/api/v1/donors …>`
3. Expected result: <exact status / output / URL to check>
4. Cleanup: `<e.g. make down>`

## ✅ Evidence
<!-- Only verification actually run, with real output lines -->
```text
Unit:         Tests run: <n>, Failures: 0, Errors: 0
Integration:  Tests run: <n>, Failures: 0, Errors: 0
Functional:   Tests run: <n>, Failures: 0, Errors: 0
Coverage:     <n> % (JaCoCo, threshold 70 %)
Trivy:        0 HIGH, 0 CRITICAL
gitleaks:     no leaks found
```

## 📸 Screenshots (added by Wadri)
<!-- Exact screenshots to take for this PR; left unchecked, Wadri uploads them -->
- [ ] Unit tests green (`make test S=<service>`)
- [ ] Integration tests green (`make test-it S=<service>`)
- [ ] Functional tests HU-<xx>
- [ ] JaCoCo report ≥ 70 %
- [ ] <manual check, e.g. curl POST /api/v1/donors → 201>

## 📋 Checklist
- [ ] Branch, commits and title follow the `yawar-pr` format (gitmoji + Conventional Commits + YAW key)
- [ ] One task only in this PR
- [ ] Unit, integration and functional tests added or updated; coverage ≥ 70 % (V-01)
- [ ] OpenAPI/AsyncAPI updated if contracts changed (V-02)
- [ ] No HIGH/CRITICAL Trivy findings (V-03); gitleaks clean
- [ ] No secrets, no real personal data, only `*.example` env files
- [ ] README / docs / CHANGELOG updated

## ⚠️ Risks and rollback
<What could break; how to revert (e.g. revert the PR, `make reset`).>

## 📝 Notes for the reviewer
<Known limitations, follow-ups, manual steps.>
