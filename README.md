# Rutube downloader for Android

An Android app with the core in Rust.

## Build rust stuff

To generate and view Kotlin bindings:
```bash
cd rust
cargo build -p rutube-ffi
cargo run -p uniffi-bindgen -- generate --library target/debug/rutube_ffi.dll --language kotlin --out-dir ../tmp/kt --no-format
```

Then view `../tmp/kt/uniffi/rutube_ffi/rutube_ffi.kt` - this is generated Kotlin file