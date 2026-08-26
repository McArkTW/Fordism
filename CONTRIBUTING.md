# Contributing

Thanks for considering it. Fordism is a small, opinionated codebase; the fastest way
to land a change is to match its opinions.

## Build locally

Everything ships via Docker (`docker compose up -d --build`), but the fast loop is
native:

| Part | Needs | Command |
|---|---|---|
| `core/` | JDK 25 | `./gradlew build` — compiles, runs every test, and the `banVar` + `banStyle` gates |
| `app/` | Node ≥ 24 | `npm ci`, then `npm start` (dev server, `/api` proxied to `localhost:8080`), `npm run lint`, `npm run test`, `npm run build` |
| `agent/` | — | shell + Dockerfile only; `docker compose --profile build-only build fordism-agent` |

CI runs exactly these plus a gitleaks secret scan — a PR that passes locally passes CI.

## The rules that fail the build

`banVar` (no `var` in Java), `banStyle` (no `cfg` identifier, no closed-set token
compared with `.equals`, no method past three parameters — records exempt), and the
app's eslint/Prettier config. They are gates precisely so nobody has to argue about
them in review; see the [Code style](./README.md#code-style) section for the
review-level rules (no DI, OOP first, explicit failure, comments say why).

## Pull requests

- Keep a PR to one idea. A fix and a refactor are two PRs.
- Tests ride with the change: a bug fix carries the test that would have caught it.
- The commit message says why; the diff already says what.
- New behavior that an operator can see belongs in the README (or a doc it links).

## No secrets, ever

Nothing in this repository may contain a real credential — CI runs gitleaks on every
PR and the history is expected to stay clean. `.env` files are git-ignored; provider
keys live per Agent Profile in the running app, not in code.
