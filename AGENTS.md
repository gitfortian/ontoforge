# OntoForge · 砺元 — AI Agent / Contributor Engineering Rules

> Scope: this repository. Product design authority and implemented behavior are separate. This file is concise operational guidance, not a duplicate architecture specification.
> Governance: [CONTRIBUTING](.github/CONTRIBUTING.md) · [OFIP-0001](doc/proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) · [Documentation index](doc/README.md)

## Purpose and verified current scope

Branch delta: [OFIP-0017 maintainer decision](https://github.com/gitfortian/ontoforge/pull/94#issuecomment-6261109442) authorizes Yak account reuse. B1 adds default local Session login/logout, CSRF and per-user cursors; OIDC/JWT is explicit compatibility mode. Read the [B1 runbook](doc/implementation/Yak-B1-本地账号Session接入.md) before changing authentication, bootstrap or deployment. Account/space administration remains later slices; no production security acceptance, release or OFIP-0005 approval is implied. Retain isolated TEST_DB_* and signed-token negative cases.

OntoForge is an independent enterprise/industrial operational ontology platform. **Do not couple it to data-ops** or claim that S0 implements authentication, trusted authorization, real object querying, connectors, production policies or Actions. S0 **does** now implement local Native ObjectType/LinkType editing and immutable schema snapshot history.

Current runnable S0-MVP-0.4 development baseline: Java 21/Spring Boot, PostgreSQL/Flyway V1–V3, React/TypeScript, real ObjectType/Property/LinkType editing, safe WIP/VALID validation, CAS revisions, immutable local Snapshot with JCS/SHA-256 and LinkType structural Diff (merged PRs #7/#11/#13). **No production release.** Bounded isolated Windows automation and the still-pending user's existing-installation/manual acceptance are recorded separately in the [current maturity ledger](doc/implementation/OntoForge-MVP-产品功能全景与成熟度矩阵.md). Native V0.2 contracts for Data Binding, ObjectSet, Semantic Execution Record and the PostgreSQL ObjectSet/Outbox experiments are **design/POC**, not deployed runtime modules.

Authoritative design references:
- [Canonical bilingual glossary (256 terms)](doc/砺元本体产品统一术语词汇表.md) — use preferred product names; never conflate LinkType/LinkFact or Local Snapshot/SchemaRelease.
- [V1.3 unified product/architecture baseline](doc/本体产品总体功能与技术架构设计_v1.3.md) — **candidate, not product/technology/security signed-off**.
- [Native DSL/AST V0.2](doc/design/核心本体元模型与Native-DSL-AST契约_v0.2.md) — candidate machine-readable design contract.
- [Current implementation and S0 acceptance](README.md) — read existing code and tests before making current-state claims.
- [Accepted OFIP-0001](doc/proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) — governance process; does not change V1.3's acceptance state.

## First inspect, then implement

1. Read the user's concrete story, affected code, callers, database migrations, API/Schema contracts, test fixtures and the accepted OFIP (when relevant). Prefer evidence over assumptions.
2. Decide the change class. **Major public or persisted contract, core domain or high-risk operation change:** proposal-only `OFIP-XXXX` PR, explicit maintainer decision, then implementation. **Routine bug, UI styling, internal refactor:** one focused code PR, no forced proposal document.
3. Implement **one user-verifiable vertical slice per feature PR**. A fully written future roadmap is not permission to generate empty Java modules, fake UI routes, stub service APIs or unowned abstractions.
4. Explain what you tested, the exact commit/CI evidence, what you did **not** run, and real user-visible acceptance instructions. A green doc or TypeScript build is not a browser, PostgreSQL or production acceptance.
5. Do not mark an OFIP Accepted, an implementation complete, or a release shipped because its PR was merged or CI succeeded. Record the explicit decision/actual evidence.

## Ownership and dependency boundaries

- `ontoforge-boot`: Spring Boot entry, HTTP/security adapters and explicit module assembly. `ontoforge-business` owns Draft/Snapshot/Binding/Object/quality and local-query-v1 ObjectSet use cases and their transactions; `ontoforge-platform` owns stored-identity authorization and platform status.
- `ontoforge-ui`: current single Vite/React application. No fictitious `apps/web`, multi-app workspace or extra shared component library before there are real consumers.
- `ontoforge-core` owns existing Native editor/schema validation; `ontoforge-dao` owns SQL, bounded PostgreSQL source reads and forward-only Flyway migrations (V1–V7 immutable; V8 local accounts). `ontoforge-configuration` owns local environment guards; `ontoforge-common` owns shared byte digests. For module changes, read the [module plan and current implementation](doc/implementation/S0-工程模块规划与命名规范.md), and run `scripts/ci/test_module_structure.py`.
- SPI/plugins/task-execution/dist and other future modules remain planned; create them when a real owner, caller, dependency direction and tests justify them.
- Domain core owns Native ObjectType/LinkType/AST/contracts, not database/HTTP/LLM implementation details. Persistence and integrations are adapters; no Core → Boot, Business → Boot or Core → Connector reverse dependency.
- Native Schema/Typed AST, semantic facts, Data Binding, ObjectSet query/evidence, deterministic Function/Decision and policy-controlled Action are **different contracts**. Do not collapse states or let a client choose its own authorization context.
- `source fact != derived projection`; `inference != authorization`; `Action requested != accepted != outcome verified`. UNKNOWN/PARTIAL/STALE/failure are not success, false or zero.

## Safety, migrations, documentation

- Explicit `mvp-local` has **no multi-user authentication/authorization/isolation**; B1 Session is a development candidate. Bind local services to loopback; do not expose to public networks or real industrial production data.
- Never commit database passwords, connection tokens, operational secrets, action permissions or sensitive source payloads to code, docs, Git history, tests or logs. `DB_PASSWORD` must be supplied externally for local PostgreSQL.
- Flyway migrations already applied must stay immutable. Add forward migrations and real database tests; do not erase local databases or use repair as a shortcut.
- Action execution and industrial physical control are **not** authorized by any prompt, model output or inference. P0/S0 must not activate dangerous tools or fabricate policy enforcement.
- Keep one current architecture entry in `doc/README.md`; do not generate new competing `architecture-latest` Markdown for a routine change. Proposal rationale lives in OFIP/PR; implemented behavior lives in code, TypeScript contracts, JavaDoc, Flyway and tests; user instructions live in the implementation runbook.
- Avoid unused factories/interfaces/managers, empty module scaffolds, magic compatibility wrappers and “defensive” layers without an observed use case.

## Commands and evidence — run only what the change requires

From repository root:
- `java -version` (Java 21), `mvn -version`, `node -v` (Node 22).
- Backend tests require an isolated loopback `ontoforge_test_*` database and `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD`; corruption tests additionally require `TEST_DB_ALLOW_FAULT_INJECTION=true`: `mvn -pl ontoforge-boot -am verify`. Startup uses separate development `DB_*`: `./scripts/run-local.ps1` builds the reactor and runs the packaged Boot jar. Direct `spring-boot:run` requires reactor dependencies installed first. Never reuse the development database for integration tests.
- Windows 11 native PostgreSQL setup and **read-only** smoke: [runbook](doc/implementation/S0-Windows11-本机PostgreSQL-启动与验收.md); `.\scripts\verify-s0.ps1` requires the same DB_* settings as the backend and a running backend.
- Frontend: Node 22.12+, `cd ontoforge-ui`, `npm ci`, `npm run build` (TypeScript + Vite), `npm run audit:dependencies`; `npm run dev` and actual browser use are separate evidence.
- Docs governance + links: `python -m unittest discover -s doc/tests -p 'test_*.py' -v`.
- Native V0.2 structural tests: `python -m pip install jsonschema==4.25.1`, then `python -m unittest discover -s doc/design/tests -p test_native_v02.py -v`.
- Research PostgreSQL experiment workflows are separate from S0 production assertions. Do not report their benchmark result as deployed ObjectSet/Action performance.

## Review and CI discipline

- Never disable a failing check, reduce assertions or change workflows solely to obtain a green badge. Correct the failure or report it.
- CI status must distinguish `PASS`, `FAIL`, `NOT SELECTED`, and `NOT RUN`; use exact scope (docs, native contract, JDBC/Flyway, frontend, UI E2E, release). No skipped case is passed.
- Include reviewed `base`/`head` SHA and actual tests in a PR; review affected callers/contracts and the full relevant diff.
- OFIP-0001 Phase 2 implements `.github/workflows/quality-gate.yml` with a stable `Required Quality Gate`, consumer-aware scope selection and genuine PASS / FAIL / NOT SELECTED evidence, including selected real product E2E. See [S0 CI trust and roll-out runbook](doc/implementation/S0-CI-可信质量门禁.md). GitHub branch protection is a separate setting and is **not** automatically enabled by the workflow.
- Keep AI-assisted changes human-reviewable and explicitly disclose assistance per the PR template.
