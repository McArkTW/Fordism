# Working on Fordism

The style bar lives in `README.md` and is enforced by `banVar` / `banStyle` in
`core/build.gradle` — a rule the build does not enforce is a suggestion. What follows is
the part a build task cannot check.

## "Verified" is a word with a definition

Do not write "verified", "tested" or "works" about a change until it has been exercised
**through the running API**, not only through the test suite you wrote alongside the code.

Bring up a throwaway instance — never the user's:

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
# POST /api/auth/bootstrap to get a session; every write needs `X-Fordism-Request: 1`.
# Tear down afterwards: docker rm -f fordism-probe && docker volume rm fordism-probe-vol
```

Under Git Bash, `export MSYS_NO_PATHCONV=1` first or every container path is rewritten
to a Windows one.

Quote the actual request and response in whatever you report. A green suite is evidence
that the code agrees with your assumptions, which is not the same as evidence that it
works.

## Write the destructive case, not the happy one

Both v1.0.0 skills bugs were shipped under 171 passing tests, because the tests and the
code were written together and encoded the same assumption:

- `writeFolder` cleared the folder before validating the upload, so a **refused** upload
  deleted the skill it was replacing. The test uploaded to a name that did not exist yet,
  so there was nothing to lose.
- `SkillPluginStore.add` checked its new folder name against other plugins but not against
  the library, so a plugin named after an existing skill folder silently took it. The test
  named the plugin `toolkit` and the skill `mine` — two names that cannot collide.

So, for anything that writes to disk:

- **Exercise the failure path against existing data.** The interesting question is never
  "does a good input work", it is "what does a rejected input leave behind".
- **Make the names collide.** If an operation derives a path from user input, write the
  case where that path is already taken.
- **State the invariant once instead of enumerating cases.** See
  `SkillDataLossTest.nothing_but_an_operation_naming_a_skill_may_touch_it`: it fingerprints
  the skills an operation did not name, runs every mutating entry point including the ones
  meant to fail, and asserts the fingerprint never moves. It caught both bugs above without
  either being anticipated — which is the only kind of test that catches your own blind spot.

A guarantee written in a javadoc is not enforced. `SkillPluginStore` claimed "a hand-written
skill is never in reach of a sync" while doing the opposite. If a comment states a
guarantee, there must be a test named after it.

## Mutation testing was tried and does not cover this

PIT (`info.solidsoft.pitest`, needs `pitestVersion = '1.30.0'` — older ships an ASM that
cannot read Java 25 class files) runs and scores the `skill` package at 56%, which is worth
knowing. It would **not** have caught either bug: the deleted-too-early call was `KILLED`,
because the line is genuinely needed and only its position was wrong, and a missing guard
cannot be produced by mutating existing code. Useful as coverage hygiene; not a substitute
for the invariant test above.

## Status files are claims, not evidence

`status.md` outside the repo is a handover note written by a previous session. Re-verify
anything it calls done before repeating it — it said the skills feature was verified, and it
was not.
