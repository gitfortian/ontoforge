# OntoForge · 砺元 — AI Agent / Contributor Engineering Rules

> Scope: this repository. Product design authority and implemented behavior are separate. This file is concise operational guidance, not a duplicate architecture specification.
> Governance: [CONTRIBUTING](.github/CONTRIBUTING.md) · [OFIP-0001](doc/proposals/OFIP-0001-需求提案文档治理与CI证据门禁.md) · [Documentation index](doc/README.md)

## Purpose and verified current scope

OntoForge is an independent enterprise/industrial operational ontology platform. **Do not couple it to data-ops** or claim that S0 already implements ontology editing, authentication, object querying, connectors, policies or Actions.

Current runnable S0-MVP-0.1: Java 21/Spring Boot, PostgreSQL/Flyway V1, React/TypeScript, and an actual `GET /api/system/status`. The Native DSL/AST V0.2 JSON Schemas and PostgreSQL ObjectSet/Outbox experiments are **contracts and research fixtures**, not finished runtime modules.

Authoritative design references:
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

- `ontoforge-boot`: current Spring Boot entry / minimal S0 JDBC+Flyway. Long term Boot is assembly/HTTP boundary; domain logic must not accumulate here as P0 grows.
- `ontoforge-ui`: current single Vite/React application. No fictitious `apps/web`, multi-app workspace or extra shared component library before there are real consumers.
- `ontoforge-core`, `ontoforge-dao`, `ontoforge-platform`, `ontoforge-spi` etc. are **planned**; create a module only when a real story, owner, caller, dependency direction and tests justify it.
- Domain core owns Native ObjectType/LinkType/AST/contracts, not database/HTTP/LLM implementation details. Persistence and integrations are adapters; no Core → Boot, Business → Boot or Core → Connector reverse dependency.
- Native Schema/Typed AST, semantic facts, Data Binding, ObjectSet query/evidence, deterministic Function/Decision and policy-controlled Action are **different contracts**. Do not collapse states or let a client choose its own authorization context.
- `source fact != derived projection`; `inference != authorization`; `Action requested != accepted != outcome verified`. UNKNOWN/PARTIAL/STale/failure are not success, false or zero.

## Safety, migrations, documentation

- S0 has **no authentication/authorization/isolation**. Bind local services to loopback; do not expose to public networks or real industrial production data.
- Never commit database passwords, connection tokens, operational secrets, action permissions or sensitive source payloads to code, docs, Git history, tests or logs. `DB_PASSWORD` must be supplied externally for local PostgreSQL.
- Flyway migrations already applied must stay immutable. Add forward migrations and real database tests; do not erase local databases or use repair as a shortcut.
- Action execution and industrial physical control are **not** authorized by any prompt, model output or inference. P0/S0 must not activate dangerous tools or fabricate policy enforcement.
- Keep one current architecture entry in `doc/README.md`; do not generate new competing `architecture-latest` Markdown for a routine change. Proposal rationale lives in OFIP/PR; implemented behavior lives in code, TypeScript contracts, JavaDoc, Flyway and tests; user instructions live in the implementation runbook.
- Avoid unused factories/interfaces/managers, empty module scaffolds, magic compatibility wrappers and “defensive” layers without an observed use case.

## Commands and evidence — run only what the change requires

From repository root:
- `java -version` (Java 21), `mvn -version`, `node -v` (Node 22).
- Backend with local PostgreSQL and environment-provided credentials: `mvn -pl ontoforge-boot -am verify`; startup `mvn -pl ontoforge-boot spring-boot:run`.
- Windows 11 native PostgreSQL setup and **read-only** smoke: [runbook](doc/implementation/S0-Windows11-本机PostgreSQL-启动与验收.md); `.\scripts\verify-s0.ps1` requires the same DB_* settings as the backend and a running backend.
- Frontend: `cd ontoforge-ui`, `npm install`, `npm run build` (TypeScript + Vite); `npm run dev` and actual browser use are separate evidence.
- Docs governance + links: `python -m unittest discover -s doc/tests -p 'test_*.py' -v`.
- Native V0.2 structural tests: `python -m pip install jsonschema==4.25.1`, then `python -m unittest discover -s doc/design/tests -p test_native_v02.py -v`.
- Research PostgreSQL experiment workflows are separate from S0 production assertions. Do not report their benchmark result as deployed ObjectSet/Action performance.

## Review and CI discipline

- Never disable a failing check, reduce assertions or change workflows solely to obtain a green badge. Correct the failure or report it.
- CI status must distinguish `PASS`, `FAIL`, `NOT SELECTED`, and `NOT RUN`; use exact scope (docs, native contract, JDBC/Flyway, frontend, UI E2E, release). No skipped case is passed.
- Include reviewed `base`/`head` SHA and actual tests in a PR; review affected callers/contracts and the full relevant diff.
- Current workflows are **not yet** an enforced unified required quality gate; OFIP-0001 plans that as a **separate implementation PR**. Do not claim repository branch protection just because a YAML file exists.
- Keep AI-assisted changes human-reviewable and explicitly disclose assistance per the PR template.
