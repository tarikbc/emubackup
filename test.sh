#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/test
CP="libs/junit-console.jar:libs/json.jar"

# The android-free source set. Every class listed here must compile and run on a
# plain JVM, which is what makes the backup and restore pipeline testable without
# an emulator or a device. Listed explicitly rather than globbed so that adding a
# class to the test set is a deliberate act.
SRC=src/main/java/com/tarikbc/emubackup
PURE_SRCS="
$SRC/Sizes.java
$SRC/Category.java
$SRC/Tier.java
$SRC/TargetStatus.java
$SRC/IdKind.java
$SRC/PathResolver.java
$SRC/PathMatcher.java
$SRC/PathPattern.java
$SRC/Grouping.java
$SRC/Target.java
$SRC/Emulator.java
$SRC/TargetRegistry.java
$SRC/FileStat.java
$SRC/FileSource.java
$SRC/FileSink.java
$SRC/LocalFileSource.java
$SRC/LocalFileSink.java
$SRC/Capabilities.java
$SRC/PackagePresence.java
$SRC/TargetScan.java
$SRC/ScanEngine.java
$SRC/Hashes.java
$SRC/VersionId.java
$SRC/ManifestFile.java
$SRC/ManifestTarget.java
$SRC/Manifest.java
$SRC/Plan.java
$SRC/DiffEngine.java
$SRC/ArchivePolicy.java
$SRC/ArchiveWriter.java
$SRC/BackupSink.java
$SRC/LocalFolderSink.java
$SRC/RestoreScript.java
$SRC/IndexEntry.java
$SRC/BackupIndex.java
$SRC/Progress.java
$SRC/EmulatorVersions.java
$SRC/BackupRunner.java
$SRC/ArchiveReader.java
$SRC/RestoreAction.java
$SRC/RestoreItem.java
$SRC/RestorePlan.java
$SRC/RestorePlanner.java
$SRC/RestoreRunner.java
$SRC/SaveGroup.java
$SRC/GroupBuilder.java
$SRC/RomFilenameParser.java
$SRC/GameNames.java
$SRC/UploadDecision.java
$SRC/DriveApi.java
$SRC/OAuthClientInput.java
$SRC/DeviceCodeAuth.java
$SRC/TokenEnvelope.java
$SRC/DriveSink.java
"

# Guard: enforce the android-free property rather than trusting it. Without this,
# one stray `import android.util.Log` silently drops a class out of the suite and
# nobody notices until a data-loss bug ships.
for f in $PURE_SRCS; do
  [ -f "$f" ] || { echo "ERROR: $f listed in PURE_SRCS but does not exist" >&2; exit 1; }
  if grep -q '^import android\.' "$f"; then
    echo "ERROR: $f is in the JVM test set but imports android.*" >&2
    echo "       Either move the android-dependent code out, or drop it from PURE_SRCS." >&2
    exit 1
  fi
done

# Note: this compiles against libs/json.jar, but the APK uses the cut-down org.json
# inside android.jar. They are not the same API — JSONObject.keySet() exists here and
# not there — so a green test run does not prove the app compiles. ./build.sh is what
# catches that, and CI runs both in this order.
javac --release 17 -cp "$CP" -d build/test $PURE_SRCS $(find test/java -name '*.java')
java -jar libs/junit-console.jar execute -cp "build/test:libs/json.jar" --scan-classpath --details=tree
