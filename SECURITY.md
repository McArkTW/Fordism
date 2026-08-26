# Security

## Deployment model — read this before running Fordism

**Never expose Fordism to the internet.** It is built for a trusted, network-gated
environment (a homelab, a private VPC, an office LAN), operated by people you would
also trust with the Docker host itself:

- **The API requires no authentication by default.** Anyone who can reach the port can
  create credentials, edit workflows, and start agent runs. Optional Bearer-token
  verification exists (`FORDISM_AUTH_PUBKEY` / `FORDISM_AUTH_ISSUER` /
  `FORDISM_AUTH_AUDIENCE` against your OIDC provider's RS256 key), but the default is
  off — the network is the gate.
- **Core mounts the host Docker socket** to spawn agent containers. Anyone who
  controls core effectively controls the Docker host.
- **Agents run with their CLI safety prompts disabled**
  (`claude --dangerously-skip-permissions`, `qwen --yolo`) inside disposable
  containers. Containment is the per-task workspace mount and the step's network
  policy — an omitted `network` joins only the launcher network; `full` really is the
  open internet.

## What the design does protect

- **Credential values are write-only.** No API endpoint ever returns a stored value
  (`ApiShapeTest` pins this); the only reader is the launcher.
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
  next task re-asks — a re-ask, never a leak to disk.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting on this repository
(Security → Report a vulnerability) rather than a public issue. Reports are read by
the maintainer; expect an acknowledgement within a week.
