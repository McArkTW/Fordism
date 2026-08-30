# Contributing

Thanks for considering it. Fordism is a small, opinionated codebase; the fastest way
to land a change is to match its opinions. By taking part you agree to the
[Code of Conduct](./CODE_OF_CONDUCT.md).

## Build locally

Everything ships via Docker (`docker compose up -d --build`), but the fast loop is
native:

| Part | Needs | Command |
|---|---|---|
| `core/` | JDK 25 | `./gradlew build` — compiles, runs every test, and the `banVar` + `banStyle` gates |
| `app/` | Node ≥ 24 | `npm ci`, then `npm start` (dev server, `/api` proxied to `localhost:8080`), `npm run lint`, `npm run test`, `npm run build` |
| `agent/` | — | shell + Dockerfile only; `docker compose --profile build-only build fordism-agent` |

CI runs exactly these plus a gitleaks secret scan — a PR that passes locally passes CI.

## Code style

Three gates plus a review bar. Every gate fails the build, so a rule here is not a suggestion.

| Gate | Covers |
|---|---|
| `./gradlew build` → `banVar` | `core` — `var` is banned, explicit types only |
| `./gradlew build` → `banStyle` | `core` — no `cfg`, no closed-set token compared with `.equals`, no method past three parameters (records exempt: a data carrier at a boundary is a definition, not a call site) |
| `ng lint` + Prettier | `app` — angular-eslint, single quotes, Tailwind class ordering |

Not gated — applied in review:

- **No DI in Java.** Construct and pass dependencies yourself; the whole graph is `Fordism.main`
  on one screen. Angular's `inject()` is idiomatic and expected in `app`.
- **OOP first.** Objects own their state and the behaviour over it — no anemic struct plus a
  `*Utils` bag. Plain DTOs at a boundary are the exception.
- **No boolean flag arguments.** A method that branches on a flag is doing two things; split it.
- **No abbreviations** outside `id url http api pr llm` — `workItemId`, never `wid`.
- **Failure and absence are explicit.** Throw with enough context to debug; return an empty
  collection or `Optional`, never a silent `null`.
- **Construction is not work.** A constructor assigns its fields; it does not touch the disk.
  Migrations and seeding are called from `Fordism.main`, where the wiring already lives.
- **Comments say why.** No restatement of the line above, no commented-out code.
- **Angular:** single-file components — split the template out past ~100 lines or when it needs
  real scoped CSS. Signals, standalone, `@if`/`@for`.

## "Verified" means through the running API

A green suite is evidence that the code agrees with the assumptions of whoever wrote
it — which is not evidence that it works. Two data-loss bugs shipped here under 171
passing tests, because the tests and the code were written together and encoded the
same assumption. So before calling anything verified, exercise it against a throwaway
instance and quote the actual request and response:

```sh
docker build -t fordism-core:probe -f core/Dockerfile .
docker run -d --name fordism-probe -p 8099:8080 \
  -e FORDISM_AUTH_LOCAL=true -e FORDISM_ADMIN_SECRET=probe-secret \
  -e FORDISM_STATE_DIR=/fordism/state -e FORDISM_SKILLS_DIR=/fordism/skills \
  -e FORDISM_WORKSPACES_DIR=/fordism/workspaces \
  -e FORDISM_USER_WORKFLOWS_DIR=/fordism/workflows \
  -e FORDISM_AGENT_PROFILES_DIR=/fordism/agent-profiles \
  -e AGENT_TEMPLATES=/fordism/templates \
  -v fordism-probe-vol:/fordism fordism-core:probe
# POST /api/auth/bootstrap, then /api/auth/login for the cookie.
# Every write needs `X-Fordism-Request: 1`.
docker rm -f fordism-probe && docker volume rm fordism-probe-vol
```

Under Git Bash, `export MSYS_NO_PATHCONV=1` first or every container path is rewritten
to a Windows one. POSIX file-mode assertions `assumeTrue` themselves away on Windows, so
anything asserting a permission has to be run in a container to mean anything.

## Write the destructive case

For anything that writes to disk, the interesting question is never "does a good input
work", it is "what does a rejected input leave behind".

- Exercise the failure path **against existing data** — not against a name that does not
  exist yet, which is what let a refused upload delete the skill it was replacing.
- **Make the names collide.** If an operation derives a path from user input, write the
  case where that path is already taken.
- **Point an escape at something that exists.** A path-traversal test aimed at a missing
  file passes against an unguarded `resolve` too — 400 for "no such file" rather than
  "not yours" — and proves nothing.
- **State the invariant once** instead of enumerating cases. See
  `SkillDataLossTest.nothing_but_an_operation_naming_a_skill_may_touch_it`.

A guarantee written in a javadoc is not enforced: if a comment states one, there must be
a test named after it.

## Pull requests

- Keep a PR to one idea. A fix and a refactor are two PRs.
- Tests ride with the change: a bug fix carries the test that would have caught it, and
  it should be run against the unfixed code first — a test that passes either way proves
  nothing.
- The commit message says why; the diff already says what.
- New behavior that an operator can see belongs in the README (or a doc it links), and
  in the CHANGELOG's unreleased section.

## No secrets, ever

Nothing in this repository may contain a real credential — CI runs gitleaks on every
PR and the history is expected to stay clean. `.env` files are git-ignored; provider
keys live per Agent Profile in the running app, not in code.
