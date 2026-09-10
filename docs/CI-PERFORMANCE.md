# CI and release performance

Measured on 2026-09-10 against the v0.1.5 workflow. Timings are observations, not
guarantees: hosted runner capacity, package mirrors, cache state, and registry
transfers vary. Elapsed time includes job scheduling; runner time sums job durations.

## Release measurements

| Run | Release path | Elapsed |
| --- | --- | --- |
| [v0.1.5](https://github.com/leo91000/leo-agent-manager/actions/runs/34419219831) | Original serial quality, image build/load, smoke test, push, deploy | 10m19s |
| [v0.1.6-rc.1](https://github.com/leo91000/leo-agent-manager/actions/runs/34421319030) | Reuse a completed main validation and its exact image | 1m34s |
| [v0.1.6-rc.2](https://github.com/leo91000/leo-agent-manager/actions/runs/34421520389) | Tag deliberately pushed before main existed; full validation fallback | 5m23s |

The fallback run includes the first cache population for the new Dockerfile.
Main at the same commit completed in
[3m55s](https://github.com/leo91000/leo-agent-manager/actions/runs/34421600808).
The tag-only experiment confirms that reuse is optional: a release still gets all
checks when there is no qualifying main run.

## Changes retained

- Build and smoke-test the candidate image alongside quality checks. Push it by
  immutable digest; publish release/latest tags only when both jobs succeed.
- On a release, reuse only a successful `push` run of this workflow on this
  repository's `main`, at the exact commit. Its `validated-image` artifact must
  match the repository, commit, run ID, schema, and SHA-256 digest. PRs and manual
  runs cannot supply release proof. Missing or invalid proof runs full CI.
- Wait for concurrent main validation instead of starting a duplicate build.
  Completed evidence is retained for 90 days. The wait is bounded at 15 minutes.
- Build once, preserving SBOM and provenance, then smoke-test the published digest.
  PR images stay local and use no registry credentials. Promotion uses the runner's
  Buildx client without starting another BuildKit daemon.
- Give browser projects separate real applications, SQLite databases, workers, and
  production rate limiters. Keep ordered persistence journeys together; run the
  four Chromium/WebKit light/dark layout matrices independently. This removes
  deliberate waits for one shared rate limiter without weakening that limiter.
- Run TypeScript checking once through `pnpm build` inside `pnpm check`. Install
  the Chromium headless shell used by the tests, plus WebKit and their OS dependencies.
- Set commit metadata after stable Docker tool layers. Keep all runtime tools,
  UID 1000, health checks, persistence checks, and exact deployed-commit validation.
- Check Docker health every second during startup and poll deployment health every
  two seconds. Deployment summaries report update, restart, and readiness timings.
- Cancel superseded main/PR runs; serialize production deployments without cancelling
  them. Keep screenshot/trace artifacts and a manual browser-worker comparison input.

## Controlled experiments

Local Docker builds changed only the commit argument between warm builds. The old
Dockerfile took **21.93s** because commit metadata invalidated tool installation;
moving it after the stable layers took **1.65s**. CI's first run still has to populate
those new cache keys, so the local number is not a cold hosted build prediction.

The isolated local browser suite passed all nine tests with two, four, and five
workers: approximately **102s**, **56.5s**, and **39.2s**, respectively. The original
hosted serial browser step took **317s**. Hosted five-worker trials took **99s** and
**126s**; a same-commit four-worker trial took
[110s](https://github.com/leo91000/leo-agent-manager/actions/runs/34421600152).
Local hardware and hosted runner contention differ, so local timings alone do not
establish the best CI worker count.

A second same-commit hosted comparison took
[129s with five workers](https://github.com/leo91000/leo-agent-manager/actions/runs/34422050574)
and [136s with four](https://github.com/leo91000/leo-agent-manager/actions/runs/34422052319).
Five remains the default: it won locally and in the repeat hosted pair, but the
hosted samples overlap and do not establish a large advantage over four. Both are
available through manual dispatch. Two was rejected after the slower local trial.

Those warm runs completed their image jobs in **85s** and **71s**, versus **174s**
originally. Their actual build/push steps took **40s** and **34s**; the rest includes
runner setup, pulling and smoke-testing the exact published digest, and cleanup.
The existing GitHub Actions cache was retained after the warm measurements.

The startup health experiment took **6.19s** before and **2.28s** after adding the
startup interval. The original hypothesis that a 30-second health interval alone
explained deployment latency was rejected: the old image was already healthy in
about six seconds. The full rc.2 Coolify operation took **34.5s**, including **32.8s**
waiting for the new commit. Image transfer and restart still matter.

Removing the duplicate type check reduced a local `pnpm check` trial from **9.48s**
to **7.15s**, with lint, all 55 unit tests, type checking, and the production build
still passing. Small single-run differences should be treated as approximate.

## Reproduce

```sh
# Hosted same-commit browser comparison; neither manual run deploys.
gh workflow run ci.yaml --ref main -f browser-workers=4
gh workflow run ci.yaml --ref main -f browser-workers=5

# Completed-run elapsed, aggregate runner, job, and step durations.
node scripts/ci-timings.mjs 34419219831 34421319030 34421520389

# Optional experiment budget: fails for a failed run or an exceeded elapsed budget.
node scripts/ci-timings.mjs RUN_ID --budget=300
```

Run `pnpm check`, `pnpm test:e2e`, and `node tests/container-smoke.mjs IMAGE` for local
validation. Browser fixtures use temporary state and the fake Codex executable;
they do not use production credentials. Browser matrices retain the existing
viewport, navigation, editor, virtual-select, activity, fullscreen, OAuth, theme,
and persistence assertions.

The implementation follows Docker's [cache invalidation rules](https://docs.docker.com/build/cache/invalidation/)
and [manifest promotion](https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/),
and Playwright's [headless-shell installation guidance](https://playwright.dev/docs/browsers).
