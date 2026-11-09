#!/usr/bin/env bash
# Builds the reduced-operator ONNX Runtime AAR for Silero VAD (arm64-v8a + armeabi-v7a) and converts the model to
# ORT format. See README.md for the why; every version is pinned here.
#
#   third_party/onnxruntime-minimal/build.sh [work-dir]      (default work dir: ~/ort-build, outside the repo)
#
# Needs: git, Python 3.12 (brew install python@3.12), the Android SDK at $ANDROID_HOME (or ~/Library/Android/sdk)
# with NDK $NDK_VERSION, and JDK 17 (brew install openjdk@17: ORT's Java build pins AGP 7.4.2, whose jlink step fails
# on JDK 21). cmake and ninja come from pip into a venv in the work dir.
set -euo pipefail

ORT_VERSION=1.31.1                 # the git tag; must match libs.versions.toml "onnxruntime" (the JVM tests use it)
NDK_VERSION=30.0.16248370          # libs.versions.toml "ndk"
MIN_SDK=24                         # libs.versions.toml "android-minSdk"
PYTHON=${PYTHON:-/opt/homebrew/bin/python3.12}
export JAVA_HOME=${JDK17:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${1:-$HOME/ort-build}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="$SDK/ndk/$NDK_VERSION"

mkdir -p "$WORK"
cd "$WORK"

# 1. Tools: cmake + ninja for the native build, onnxruntime (same version) to convert the model.
[ -x venv/bin/python ] || "$PYTHON" -m venv venv
venv/bin/pip install -q "cmake==4.4.4" "ninja==1.13.2" "onnxruntime==$ORT_VERSION" "onnx==1.23.2" "flatbuffers==25.12.19" "numpy==2.5.4"
export PATH="$WORK/venv/bin:$PATH"

# 2. The model in ORT format (graph optimised offline at level "all", fixed style) and the operators it needs.
rm -rf model && mkdir model
cp "$REPO/shared/src/iosMain/composeResources/files/silero_vad.onnx" model/   # the published v5 model; iOS ships it as is
python -m onnxruntime.tools.convert_onnx_models_to_ort model --optimization_style Fixed --enable_type_reduction
cp model/silero_vad.ort "$REPO/shared/src/androidMain/composeResources/files/silero_vad.ort"
cp model/required_operators_and_types.config "$HERE/required_operators_and_types.config"

# 3. ONNX Runtime source at the tag.
[ -d src ] || git clone --depth 1 --branch "v$ORT_VERSION" https://github.com/microsoft/onnxruntime.git src

# 4. One native build per ABI. Same flags as the official AAR script (tools/ci_build/github/android/
#    build_aar_package.py) minus vcpkg (its asset cache is Microsoft-internal; cmake FetchContent gets the same
#    pinned deps) and minus the execution providers, plus the size-reduction flags. C++ exceptions stay ON on purpose:
#    without them any ORT error aborts the app instead of throwing, and the app falls back to the energy detector
#    on a thrown error.
COMMON=(
  --config MinSizeRel --android --android_sdk_path "$SDK" --android_ndk_path "$NDK" --android_api "$MIN_SDK"
  --cmake_generator Ninja --parallel --skip_tests --build_shared_lib --build_java --enable_lto
  --minimal_build --disable_ml_ops --no_telemetry
  --include_ops_by_config "$HERE/required_operators_and_types.config" --enable_reduced_operator_type_support
  --cmake_extra_defines CMAKE_POLICY_VERSION_MINIMUM=3.5 onnxruntime_BUILD_UNIT_TESTS=OFF --compile_no_warning_as_error
)
# --skip_tests only skips running ORT's unit tests, which do not compile for armeabi-v7a under -Werror, so they are off;
# with them off, an unused constant in MLAS's KleidiAI code (a warning) fails arm64 under -Werror, so warnings stay warnings.
OUT="$WORK/out"
JNI="$OUT/jnilibs"
for ABI in arm64-v8a armeabi-v7a; do
  python src/tools/ci_build/build.py "${COMMON[@]}" --android_abi "$ABI" --build_dir "$OUT/$ABI"
  mkdir -p "$JNI/$ABI"
  cp "$OUT/$ABI/MinSizeRel/libonnxruntime.so" "$OUT/$ABI/MinSizeRel/libonnxruntime4j_jni.so" "$JNI/$ABI/"
done

# 5. The AAR (Java API + both ABIs), with ORT's own gradle project, as build_aar_package.py does (its 'publish' is
#    skipped: step 6 takes the release AAR straight from the build).
cd src/java
ANDROID_HOME="$SDK" ANDROID_NDK_HOME="$NDK" ./gradlew --no-daemon -b=build-android.gradle -c=settings-android.gradle \
  -DjniLibsDir="$JNI" -DbuildDir="$OUT/aar" -DheadersDir="$OUT/arm64-v8a/MinSizeRel/android/headers" \
  -DpublishDir="$OUT/aar_out" -DminSdkVer="$MIN_SDK" -DtargetSdkVer=34 -DENABLE_TRAINING_APIS=0 \
  clean assembleRelease
cd "$WORK"

AAR="$OUT/aar/outputs/aar/onnxruntime-release.aar"

# 6. Vendor: our own coordinates in the repo's local Maven repo (settings.gradle.kts takes this module from nowhere
#    else), so it can never be confused with Microsoft's full package of the same version on Maven Central.
DEST="$HERE/maven/com/circaso/thirdparty/onnxruntime-android-silero/$ORT_VERSION"
rm -rf "$HERE/maven" && mkdir -p "$DEST"
cp "$AAR" "$DEST/onnxruntime-android-silero-$ORT_VERSION.aar"
cat > "$DEST/onnxruntime-android-silero-$ORT_VERSION.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.circaso.thirdparty</groupId>
  <artifactId>onnxruntime-android-silero</artifactId>
  <version>$ORT_VERSION</version>
  <packaging>aar</packaging>
  <description>ONNX Runtime $ORT_VERSION (MIT, Microsoft) for Android, reduced to the operators of Silero VAD v5. Built by third_party/onnxruntime-minimal/build.sh.</description>
  <licenses><license><name>MIT License</name><url>https://github.com/microsoft/onnxruntime/blob/main/LICENSE</url></license></licenses>
</project>
POM
( cd "$DEST" && for f in *.aar *.pom; do shasum -a 256 "$f"; done )
unzip -l "$DEST/onnxruntime-android-silero-$ORT_VERSION.aar" | grep -E "\.so$"
echo "done: $DEST"
