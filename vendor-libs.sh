#!/usr/bin/env bash
# Vendors the AndroidX + Kotlin + Shizuku deps that EmuBackup's Gradle-free
# build needs into libs/ (dexed class jars) and libs/aar/<name>/ (res + manifest
# for the resource-bearing AARs, linked via aapt2 --extra-packages in build.sh).
#
# libs/ is git-ignored, so run this once after a fresh clone (and it is the
# authoritative record of exactly which artifacts/versions the build expects).
# Idempotent — safe to re-run.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p libs libs/aar
G="https://dl.google.com/dl/android/maven2"
MC="https://repo1.maven.org/maven2"

fetch() { curl -fsSL -o "$2" "$1" && echo "  ok $(basename "$2")"; }

# aar <repo-base> <group/path> <artifact> <version> <res|"">
#   Downloads the .aar (falling back to .jar), drops classes.jar into libs/,
#   and for res-bearing artifacts copies res/ + AndroidManifest.xml into
#   libs/aar/<artifact>/ (build.sh compiles those and adds --extra-packages).
aar() {
  local repo="$1" path="$2" art="$3" ver="$4" res="$5"
  local base="$repo/$path/$art/$ver/$art-$ver" tmp; tmp="$(mktemp -d)"
  if curl -fsSL -o "$tmp/a.aar" "$base.aar" 2>/dev/null; then
    (cd "$tmp" && unzip -oq a.aar)
    cp "$tmp/classes.jar" "libs/$art-$ver.jar"
    if [ "$res" = res ] && [ -d "$tmp/res" ]; then
      rm -rf "libs/aar/$art"; mkdir -p "libs/aar/$art"
      cp -r "$tmp/res" "libs/aar/$art/"
      cp "$tmp/AndroidManifest.xml" "libs/aar/$art/"
    fi
    echo "  ok $art-$ver${res:+ (res)}"
  else
    fetch "$base.jar" "libs/$art-$ver.jar"
  fi
  rm -rf "$tmp"
}

# Back-compat wrapper so the AndroidX call sites stay short and unchanged.
androidx() { aar "$G" "$@"; }

echo "AndroidX (RecyclerView + its transitive deps):"
androidx androidx/annotation                 annotation                   1.7.1  ""
androidx androidx/collection                 collection                   1.1.0  ""
androidx androidx/core                       core                         1.13.1 res
androidx androidx/core                       core-ktx                     1.13.1 ""   # ViewGroupKt — poolingcontainer needs it on detach (crash if missing)
androidx androidx/lifecycle                  lifecycle-common             2.6.2  ""
androidx androidx/lifecycle                  lifecycle-runtime            2.6.2  res
androidx androidx/arch/core                  core-common                  2.2.0  ""
androidx androidx/arch/core                  core-runtime                 2.2.0  ""
androidx androidx/customview                 customview                   1.0.0  ""
androidx androidx/customview                 customview-poolingcontainer  1.0.0  res
androidx androidx/interpolator               interpolator                 1.0.0  ""
androidx androidx/recyclerview               recyclerview                 1.3.2  res

# Shizuku — optional privileged file access for saves under Android/data, which
# Android 11+ closes off to normal apps (MANAGE_EXTERNAL_STORAGE does not cover
# it and SAF cannot target it). All four AARs are resource-free (R.txt is 0
# bytes, no res/ entry), so they take the no-res path above and never touch the
# --extra-packages machinery. Their only other dependency is androidx.annotation,
# already vendored above. ~58 KB total.
echo "Shizuku (optional app-private storage access):"
aar "$MC" dev/rikka/shizuku api      13.1.5 ""
aar "$MC" dev/rikka/shizuku provider 13.1.5 ""
aar "$MC" dev/rikka/shizuku aidl     13.1.5 ""
aar "$MC" dev/rikka/shizuku shared   13.1.5 ""

echo "Kotlin stdlib:"
fetch "$MC/org/jetbrains/kotlin/kotlin-stdlib/1.6.21/kotlin-stdlib-1.6.21.jar" libs/kotlin-stdlib-1.6.21.jar

echo "Test-only (JUnit + org.json for ./test.sh):"
fetch "$MC/org/json/json/20240303/json-20240303.jar" libs/json.jar
fetch "$MC/org/junit/platform/junit-platform-console-standalone/1.10.2/junit-platform-console-standalone-1.10.2.jar" libs/junit-console.jar

echo "Done. libs/ populated."
