# Vendored copy

Source: https://github.com/rajbot/ts-to-mp4 at commit `6035fd7` (2026-01-13),
published to crates.io as `ts-to-mp4` 0.1.0. Licence AGPL-3.0, unchanged — it is
why this project is AGPL-3.0 too.

## Local changes

- `thiserror` moved from `"1"` to the workspace version, 2.x, so the project has
  one version of it instead of two. Needed no code changes.
- `crate-type` narrowed from `["cdylib", "rlib"]` to `["rlib"]`. The `cdylib`
  output existed for the author's WebAssembly build, which we do not use.
- Test fixtures renamed from `.ts` to `.m2t`, and the two `include_bytes!` paths
  in `tests/integration_tests.rs` updated to match. `.ts` is the TypeScript
  extension for every JetBrains IDE, so a 1.6 MB transport
  stream was being parsed as TypeScript source and reported hundreds of thousands of
  errors. `.m2t` is a conventional extension for MPEG-2 transport streams.

## Removed from the copy

`.git`, `target/` (84 MB of build cache), `.github/` (upstream CI), `Cargo.lock`
(a workspace member uses the workspace lock), and the WebAssembly scaffolding —
`web/`, `build-wasm.sh`, `scripts/`, `examples/`.

Kept: `src/`, `tests/` with its fixtures, `LICENSE`, `README.md`.

## Upstream tests

All 21 pass as a workspace member, and `cargo clippy --all-targets -- -D warnings`
is silent on it. `test_aac_sample_rates` and the two timing comparisons against
ffmpeg are the ones worth keeping green — they cover exactly the defect that
disqualified the MIT-licensed alternative `ts2mp4`.
