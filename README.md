# Rutube downloader for Android

An Android app with the core in Rust.

## Build the whole application with Gradle (and debug)

```bash
gradlew :app:installDebug
```

This command builds everything:
- `cargo build` for arm64,
- bindgen
- compile Kotlin (both generated and `app/src/*.kt`)
- assembles APK
- adb install

To just build APK (without adb install) - `gradlew :app:assembleDebug`

To run with `adb` and view logs (connect to the phone with USB cable):
```bash
adb shell am start -n io.github.nlinker.rutubedl/.MainActivity
adb logcat -s AndroidRuntime:E  # show errors only
```

## Build rust stuff

To generate and view Kotlin bindings:
```bash
cd rust
cargo build -p rutube-ffi
cargo run -p uniffi-bindgen -- generate --library target/debug/rutube_ffi.dll --language kotlin --out-dir ../tmp/kt --no-format
```

Then view `../tmp/kt/uniffi/rutube_ffi/rutube_ffi.kt` - this is generated Kotlin file

## Build Rust for Android with `cargo-ndk`

### Setup

Needs the NDK (follow Google's instruction to install NDK locally
`cargo install cargo-ndk`, `rustup target add aarch64-linux-android`
and `ANDROID_NDK_HOME` pointing at the NDK.

```bash
export ANDROID_NDK_HOME=/home/nick/android/ndk/30.0.15729638
cargo install cargo-ndk
rustup target add aarch64-linux-android
```

### Build

Executed from the `rutube-dl-droid/rust`

```bash
cargo ndk -P 24 -t arm64-v8a check -p rutube-ffi
cargo ndk -P 24 -t arm64-v8a build -p rutube-ffi
```

Then check `./rust/target/aarch64-linux-android/debug/librutube_ffi.so` - this is the expected FFI layer built.
`-P` is the API level and must match the app's `minSdk`.
