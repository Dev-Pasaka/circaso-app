# ONNX Runtime, reduced to Silero VAD

The Android app runs Silero VAD v5 (Asha's voice barge-in, approved 2026-11-07) on ONNX Runtime. Microsoft's
`onnxruntime-android` package carries every operator and was the largest thing in the bundle (32.6 MB arm64 +
23.1 MB armeabi-v7a uncompressed). This is the same runtime, same version, rebuilt from source with only the
operators and types Silero uses (ORT's "reduced operator build", minimal build: ORT-format models only).

| | full 1.31.1 | this build |
|---|---|---|
| `libonnxruntime.so` arm64-v8a | 32.6 MB | 2.92 MB |
| `libonnxruntime.so` armeabi-v7a | 23.1 MB | 1.60 MB |

## What's here

- `maven/com/circaso/thirdparty/onnxruntime-android-silero/1.31.1/`: the AAR (Java API `ai.onnxruntime.*`, unchanged,
  plus both ABIs) as a local Maven repo. `settings.gradle.kts` takes `com.circaso.thirdparty:onnxruntime-android-silero`
  from here only (`exclusiveContent`); `libs.versions.toml` `onnxruntime-android` points at it.
- `required_operators_and_types.config`: the operators and types the build keeps, generated from the model.
- `build.sh`: the whole build, every version pinned. Rerun it to rebuild.

The model the app loads is `shared/src/androidMain/composeResources/files/silero_vad.ort`, converted from the published
`shared/src/iosMain/composeResources/files/silero_vad.onnx` (iOS still ships that on the full `onnxruntime-objc` pod).

## Equivalence

`SileroVadOrtFormatTest` (androidHostTest) runs the original `.onnx` the way the app used to load it, and the `.ort`
the app now ships, through the app's own `AndroidVadEngine`. Over speech, quiet speech, speech in noise, silence,
hiss, hum and silence-speech-silence it checks that the per-window probabilities agree within 1e-4, and that the
barge-in detector emits the same edges at the same milliseconds, on loudspeaker and headphones. ONNX Runtime applies no
graph optimisation to an ORT-format model at load (full or minimal build), so the host test on full `onnxruntime-jvm`
runs the same saved graph as the phone.

## Rebuilding (e.g. a new ORT version or a new model)

1. Set `ORT_VERSION` in `build.sh` to the new tag and `onnxruntime` in `gradle/libs.versions.toml` to match (the host
   tests use the full JVM build of that version). Keep `NDK_VERSION` and `MIN_SDK` in step with the project.
2. Install the tools once: `brew install python@3.12 openjdk@17` (JDK 17 because ORT's Java build pins AGP 7.4.2,
   whose jlink step fails on JDK 21), the Android SDK with the project's NDK.
3. `third_party/onnxruntime-minimal/build.sh` (work dir defaults to `~/ort-build`, needs about 6 GB; about 25 minutes
   on an M-series Mac for both ABIs). It converts the model, rebuilds both ABIs, and replaces the vendored AAR.
4. Run `SileroVadOrtFormatTest` and `SileroVadModelTest`, then `rm -rf ~/ort-build`.

What `build.sh` does, step by step:

1. venv with `cmake 4.4.4`, `ninja 1.13.2`, `onnxruntime 1.31.1`, `onnx 1.23.2`, `flatbuffers 25.12.19`, `numpy 2.5.4`.
2. `python -m onnxruntime.tools.convert_onnx_models_to_ort model --optimization_style Fixed --enable_type_reduction`.
   It writes `silero_vad.ort` (graph optimised offline at level "all") and `required_operators_and_types.config`.
3. `git clone --depth 1 --branch v1.31.1 https://github.com/microsoft/onnxruntime.git` (commit `6af1a932e228`).
4. Per ABI (arm64-v8a, armeabi-v7a), `tools/ci_build/build.py --config MinSizeRel --android --android_api 24
   --android_ndk_path <NDK 30.0.16248370> --cmake_generator Ninja --parallel --skip_tests --build_shared_lib
   --build_java --enable_lto --minimal_build --disable_ml_ops --no_telemetry --include_ops_by_config <config>
   --enable_reduced_operator_type_support --cmake_extra_defines CMAKE_POLICY_VERSION_MINIMUM=3.5
   onnxruntime_BUILD_UNIT_TESTS=OFF --compile_no_warning_as_error`. These are the flags of ORT's own AAR script
   (`tools/ci_build/github/android/build_aar_package.py`) without vcpkg (its asset cache is Microsoft-internal; cmake
   fetches the same pinned deps) and without the NNAPI/XNNPACK/WebGPU providers, plus the size flags. ORT's unit tests
   are off because they don't compile for armeabi-v7a under `-Werror`; with them off, an unused constant in MLAS's
   KleidiAI code fails arm64 under `-Werror`, so warnings stay warnings.
5. ORT's `java/build-android.gradle` (`clean assembleRelease`) packs the two ABIs and the Java API into the AAR.
6. The AAR is copied to `maven/` under our own coordinates, with a minimal POM.

C++ exceptions stay **on** (no `--disable_exceptions`): without them any ORT error aborts the process, where now it
throws and the voice screen falls back to its energy detector.
