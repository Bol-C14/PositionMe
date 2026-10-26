# M3 — Application state and core journeys

Base: M2 `5ac25b0406bb293c544c6d57c152e9320c4d76be`. Upstream develop rechecked on 2026-10-26: `591e07c05682e9acccd974b5bb6e4ca049c20a9b`, already included.

## Acceptance

1. Recording and calibration controls render one lifecycle-owned immutable state. Start/save commands, errors, export warnings and one-shot navigation survive view recreation without duplicate commands.
2. Permissions are requested from explicit user intentions; denial offers a usable retry/settings path and never prevents access to local sessions. Optional features do not block core collection.
3. Saved sessions work offline; remote catalog loading is explicit, cancellable, and has distinct loading/empty/error/retry states. Pending local actions survive screen recreation.
4. Correction preview rejects empty/nonfinite/invalid step lengths, never divides by an invalid baseline, and explains that visual preview does not mutate saved scientific data. Primary controls have labels, adequate touch targets and status announcements.
5. Clean Debug/unsigned Release builds, meaningful state/permission/validation tests, lint and dependency boundaries pass locally. No hosted CI. New Android runtime limitations remain explicit.

## Implementation choices

Use existing AndroidX ViewModel/LiveData and Java-compatible narrow interfaces around the recording facade. Stabilize lifecycle and user journeys before language migration. Kotlin/Compose are optional follow-on migrations, not reasons to rewrite working domain algorithms or change recording formats in this milestone. Preserve M2 persistence and M1 numerical behavior.

## Scope boundaries

Device background/permission/media validation, remote resumable sync, credentials/server authentication migration, full raw-event replay, battery/storage qualification and distribution remain M4 or separately scoped follow-on work. This milestone does not upgrade dependencies beyond the current lifecycle baseline, introduce CI, or merge the preceding milestone PRs.
