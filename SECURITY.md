# Security

## Deployment model — read this before running Fordism

**Running a workflow runs code on your Docker host.** Fordism is built for a trusted,
network-gated environment (a homelab, a private VPC, an office LAN), operated by people
you would also trust with the Docker host itself. That is the same bargain a Jenkins
job or its script console makes; treat an operator account as shell access.

- **Auth is always on, but an operator account is a powerful thing.** Every API route
  (beyond health/version and the login surface) requires a session and a permission;
  sign-in is local accounts, Google, GitHub, or Microsoft (Entra ID), and OAuth logins
  map only to existing users or an explicit email/domain allowlist. Local sign-in is
  rate-limited and can require a second factor (TOTP). Still: anyone with
  `workflow.write` + `workflow.run` can make an agent execute arbitrary code in a
  container on your Docker host.
- **Core mounts the host Docker socket** to spawn agent containers. Anyone who
  controls core effectively controls the Docker host.
- **Agents run with their CLI safety prompts disabled**
  (`claude --dangerously-skip-permissions`, `qwen --yolo`) inside disposable
  containers that drop all Linux capabilities, forbid privilege escalation, and are
  capped on memory/CPU/PIDs. Containment is the per-task workspace mount and the step's
  network policy, which is **off by default** — a step gets no network unless it
  declares `fordism-only` (reach core) or `full` (the open internet).

**Exposing it beyond a trusted network.** The hardening above (login lockout, TOTP,
the sandboxed agent, the audit log) makes Fordism safe for a wider, *authenticated*
group — not safe as a public service, because the host-socket bargain stands. If you
put it on a less-trusted network, front it with TLS and an authenticating reverse proxy
or VPN, set `FORDISM_COOKIE_SECURE=true` and `FORDISM_TRUSTED_PROXY=true`, and isolate
the agent runtime from the host (rootless or socket-proxied Docker, or a sandbox
runtime such as gVisor or Kata) so a compromised agent cannot reach the machine.

## What the design does protect

- **Access is permissioned.** Routes declare dot-named permissions
  (`workflow.run`, `credential.write`, …); groups hold users and grant patterns
  (trailing `.*` covers descendants, `*` covers all). Sessions are opaque HttpOnly
  cookies (`SameSite=Lax`, `Secure` when `FORDISM_COOKIE_SECURE=true`); mutating
  requests additionally require a custom header, and the admin bootstrap secret works
  exactly once. The auth test suite pins that every non-exempt route rejects the
  unauthenticated (401) and the under-privileged (403).
- **Credential values are write-only.** No API response shape ever carries a stored
  value (`ApiShapeTest` pins this); the only reader is the launcher.
- **Grants are explicit and captured at seed time.** An agent receives exactly the
  environment variables its Agent Template declared (`CredentialStoreTest` pins this),
  and editing a template cannot change what an already-queued task receives.
- **Secrets stay out of transcripts.** A credential supplied while answering a parked
  run travels beside the message and is injected as an environment variable — never
  into the resume prompt, which is written to the on-disk session transcript.
- **Secrets stay out of `docker run` argv** (a 0600 `--env-file`, deleted after
  launch) — though they remain visible to anyone who can run `docker inspect`, which
  is consistent with "whoever holds the Docker host holds everything".
- Rescue-supplied secrets live only in core's memory: a restart forgets them and the
  next task re-asks — a re-ask, never a leak to disk. A rescue secret reaches only the
  step that declared or asked for its key, not every later container in the run.
- **API tokens narrow, never widen.** A personal token holds at most the permissions of
  the account that minted it, is stored as a hash (shown once), cannot mint or revoke a
  token, and dies with its account.
- **Every change is audited.** Sign-ins, writes, and token mints are recorded (actor,
  IP, action, allowed/refused) to an append-only log an admin can read.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting on this repository
(Security → Report a vulnerability) rather than a public issue. Reports are read by
the maintainer; expect an acknowledgement within a week.
