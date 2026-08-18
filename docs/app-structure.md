# App structure: Rust native core + thin UI shell

Start with a console Rust library and application (like yt-dlp but with a basic CLI), where the true value is the Rust
library that accepts logins, keeps cookies, and does everything needed to download a video file. Android UI on top of it
afterwards.

## Workspace layout

```
rutube-dl-droid/
├── rust/
│   ├── Cargo.toml            # workspace root
│   ├── rutube-core/          # all the logic. no FFI, no Android
│   ├── rutube-cli/           # thin binary, phase test harness
│   └── rutube-ffi/           # UniFFI wrapper, cdylib
├── android/
│   ├── settings.gradle.kts
│   └── app/
└── docs/
```

Cargo workspace rooted at `rust/`, Gradle rooted at `android/`. They stay independent; `rust-android-gradle` points at
`../rust/rutube-ffi`.

## Why to keep `rutube-ffi` separate? 

This is to make `rutube-core` to be responsible for core itself. Core carries no UniFFI macros, no `cdylib` crate type,
no FFI-shaped types. It stays a normal Rust library that `cargo test` exercises and that reads like ordinary code. The
FFI crate is a translation layer and nothing else. This is what uniffi-starter means by functional core / imperative
shell.
