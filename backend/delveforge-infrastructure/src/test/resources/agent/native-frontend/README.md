# Native frontend fixtures

`npm/` supplies the Vue/Vite source and npm v3 lock used by the deterministic Docker delivery tests.
The postinstall deliberately exits 99; these fixtures install with the native `--ignore-scripts` option. The generic runtime itself permits lifecycle scripts inside the same isolated command boundary.

On 2026-11-03, the trusted final protocol-v8 JDK 21 image generated:

- `npm2/package-lock.json`: npm 10.9.2 converted the existing v3 lock with
  `npm install --package-lock-only --lockfile-version=2 --ignore-scripts --no-audit --no-fund --offline`.
  It includes the native legacy `dependencies` graph, rather than only changing the version field.
- `pnpm6/pnpm-lock.yaml`: pinned pnpm 8.15.9 ran
  `pnpm install --lockfile-only --ignore-scripts --registry=https://registry.npmjs.org/`
  against the same manifest with packageManager pnpm@8.15.9, inside a disposable fixture directory.
  The checked-in lock retains native package identities, relationships and integrity values.

These are test inputs. The product uses original locks and native ci/frozen semantics; it does not convert source locks.

On 2026-11-07, the protocol-v9 image with pinned pnpm 12.9.1 regenerated `pnpm/pnpm-lock.yaml` using
`pnpm install --lockfile-only --ignore-scripts --registry=https://registry.npmjs.org/`.
This adds the native environment-lock document for the existing packageManager pin;
the project dependency graph and integrity values remain unchanged. The fixture uses
native frozen installation, without disabling package-manager lock validation.
