#!/bin/bash
# Builds the SAI Pen APK: Winlator (see UPSTREAM) + patches/winlator.patch +
# our own files in src/ + third_party/wintab/wintab32.dll.
#
#   ./build.sh                 tests, then the APK into dist/
#   ./build.sh release         tests, then the APK to give to others into
#                              dist/release/: not debuggable, signed with the
#                              release key (see RELEASE_KEY below)
#   ./build.sh test            tests only (C wire test + JVM unit tests)
#   ./build.sh save-patches    write the build tree's changes to Winlator's
#                              own files back into patches/winlator.patch
#   ./build.sh check-apk F.apk fail if anything we may not pass on is in F.apk
#                              (Microsoft's files, the FDK AAC codec, Winlator's
#                              programs SAI does not use)
#
# The Winlator checkout lives OUTSIDE this repo (it is ~150 MB of binaries):
# $SAIPEN_WORK, default ~/Documents/sai-android-build.
#   winlator-pristine/  untouched clone at the UPSTREAM commit
#   winlator-app/       the build tree: pristine + patch + our files
#
# Needs: JDK 17 (Gradle 8.14 does not run on newer Java), Android SDK with
# NDK 24.0.8215888, cmake 3.22.1 and platform 35; MinGW-w64 for winhandler.exe
# (brew install mingw-w64).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${SAIPEN_WORK:-$HOME/Documents/sai-android-build}
PRISTINE=$WORK/winlator-pristine
TREE=$WORK/winlator-app
PATCH=$HERE/patches/winlator.patch
MODE=${1:-all}

URL=$(sed -n 's/^url=//p' "$HERE/UPSTREAM")
COMMIT=$(sed -n 's/^commit=//p' "$HERE/UPSTREAM")

if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
           /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home; do
    if [ -x "$j/bin/java" ]; then export JAVA_HOME=$j; break; fi
  done
fi
SDK=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
# The release signing key: never in the repository. Every update has to be
# signed with the same key, or Android will not install it over the old app:
# losing it means everyone reinstalls. Keep a copy of the whole folder.
RELEASE_KEY=${SAIPEN_RELEASE_KEY:-$WORK/release-key/keystore.properties}
export SAIPEN_RELEASE_KEY=$RELEASE_KEY

# Our changes to Winlator's own files. Not the .tzst archives: the build
# rewrites those itself (below), and their diff would be hundreds of MB.
tree_diff() {
  git -C "$TREE" diff --binary -- . ':(exclude,glob)app/src/main/assets/**/*.tzst'
}

# ---- save-patches ----------------------------------------------------------
if [ "$MODE" = save-patches ]; then
  mkdir -p "$HERE/patches"
  # tracked files only: our own files in the tree are untracked copies of src/
  tree_diff > "$PATCH"
  echo "saved $(grep -c '^diff --git' "$PATCH") changed file(s) to patches/winlator.patch"
  exit 0
fi

# Nothing of Microsoft's may be in the APK: their licences do not let us
# pass on their DLLs or fonts. Nor the FDK AAC codec: its licence is
# incompatible with the GPL and asks for an AAC patent licence. Nor Winlator's
# extra programs SAI never uses (7-Zip, wfm.exe, winaddons.exe and others):
# each would need its source in the release, and some have none. Looks at every
# file of an unpacked APK (or assets folder) and every file inside its .tzst
# archives; what counts is said in tools/rewrite_tar.py (forbidden_reason).
no_forbidden() {
  local dir=$1 found bad=0 t
  while IFS= read -r t; do
    found=$(zstd -q -dc "$dir/$t" | python3 -I "$HERE/tools/rewrite_tar.py" --list-forbidden)
    if [ -n "$found" ]; then echo "  FAIL in $t:"; echo "$found" | sed 's/^/         /'; bad=1; fi
  done < <(cd "$dir" && find . -name '*.tzst' | sed 's#^\./##' | sort)
  found=$(tar -C "$dir" --exclude '*.tzst' --exclude '*saipen/LICENSES.txt' -cf - . | python3 -I "$HERE/tools/rewrite_tar.py" --list-forbidden)
  if [ -n "$found" ]; then echo "  FAIL outside the archives:"; echo "$found" | sed 's/^/         /'; bad=1; fi
  [ $bad = 0 ] && echo "  ok   nothing of Microsoft's, no FDK AAC and no unused Winlator program in $(find "$dir" -name '*.tzst' | wc -l | tr -d ' ') archive(s) and the files around them"
  return $bad
}

if [ "$MODE" = check-apk ]; then
  apk=${2:?usage: ./build.sh check-apk F.apk}
  # read from the zip itself (tools/apk_entries.py says why not unzip)
  E="python3 -I $HERE/tools/apk_entries.py"
  echo "== nothing of Microsoft's, no FDK AAC, no unused Winlator program in $(basename "$apk") =="
  rc=0; n=0
  while IFS= read -r t; do
    n=$((n + 1))
    found=$($E --cat "$apk" "$t" | zstd -q -dc | python3 -I "$HERE/tools/rewrite_tar.py" --list-forbidden)
    if [ -n "$found" ]; then echo "  FAIL in $t:"; echo "$found" | sed 's/^/         /'; rc=1; fi
  done < <($E --list-tzst "$apk")
  found=$($E --tar "$apk" | python3 -I "$HERE/tools/rewrite_tar.py" --list-forbidden)
  if [ -n "$found" ]; then echo "  FAIL outside the archives:"; echo "$found" | sed 's/^/         /'; rc=1; fi
  [ $rc = 0 ] && echo "  ok   nothing of Microsoft's, no FDK AAC and no unused Winlator program in $n archive(s) and the files around them"
  exit $rc
fi

# ---- tests that need no Winlator -------------------------------------------
# SAIPEN_NO_TESTS=1 skips every test step: a quick try-out build only.
NO_TESTS=${SAIPEN_NO_TESTS:-0}
if [ "$NO_TESTS" != 1 ]; then
echo "== DLL side of the pen wire (tests/test_android_wire.c) =="
CBIN=$(mktemp -d)/test_android_wire
cc -Wall -Wextra -Werror -fsanitize=address,undefined -fno-sanitize-recover=all \
   -o "$CBIN" "$HERE/tests/test_android_wire.c"
"$CBIN" "$HERE/tests/fixtures/android-pen-wire.txt"

echo "== winhandler.exe's side of the wire (tests/test_winhandler_protocol.c) =="
WBIN=$(mktemp -d)/test_winhandler_protocol
cc -Wall -Wextra -Werror -fsanitize=address,undefined -fno-sanitize-recover=all \
   -o "$WBIN" "$HERE/tests/test_winhandler_protocol.c"
"$WBIN" | tail -1

echo "== what the archive rewrite leaves out (tests/test_rewrite_tar.py) =="
out=$(python3 -I "$HERE/tests/test_rewrite_tar.py" 2>&1) || { echo "$out"; exit 1; }
echo "  ok   $(echo "$out" | /usr/bin/grep -o 'Ran [0-9]* tests*')"
fi

# ---- the build tree --------------------------------------------------------
if [ ! -d "$PRISTINE/.git" ]; then
  echo "== cloning Winlator $COMMIT =="
  mkdir -p "$WORK"
  git clone --filter=blob:none "$URL" "$PRISTINE"
  git -C "$PRISTINE" checkout -q "$COMMIT"
fi
# gradlew must be executable to run, but that is not a change to Winlator
git -C "$PRISTINE" config core.fileMode false
have=$(git -C "$PRISTINE" rev-parse --short=7 HEAD)
if [ "$have" != "${COMMIT:0:7}" ]; then
  echo "winlator-pristine is at $have, UPSTREAM says $COMMIT"; exit 1
fi

# Do not throw away work done in the build tree: its changes must already be
# in the patch (./build.sh save-patches) before the tree is rebuilt.
if [ -d "$TREE/.git" ]; then
  if ! cmp -s <(tree_diff) <( [ -f "$PATCH" ] && cat "$PATCH" || true ); then
    echo "The build tree has changes that are not in patches/winlator.patch."
    echo "Run ./build.sh save-patches first, or delete $TREE to drop them."
    exit 1
  fi
fi

echo "== preparing the build tree =="
rsync -a --delete "$PRISTINE/" "$TREE/"
chmod +x "$TREE/gradlew"
[ -f "$PATCH" ] && git -C "$TREE" apply --binary "$PATCH"
APP=$TREE/app/src
rsync -a "$HERE/src/main/java/" "$APP/main/java/"
# our icons (new names, so no Winlator file is overwritten)
rsync -a "$HERE/src/main/res/" "$APP/main/res/"
mkdir -p "$APP/test/java" "$APP/test/resources" "$APP/main/assets/saipen"
rsync -a "$HERE/src/test/java/" "$APP/test/java/"
cp "$HERE/tests/fixtures/android-pen-wire.txt" "$APP/test/resources/"
cp "$HERE/third_party/wintab/wintab32.dll" "$APP/main/assets/saipen/wintab32.dll"
# our winhandler.exe, in place of Winlator's (whose source is not published)
x86_64-w64-mingw32-gcc -O2 -Wall -Wextra -Werror -municode -mwindows -static \
  -o "$APP/main/assets/saipen/winhandler.exe" "$HERE/winhandler/winhandler.c" \
  -lws2_32 -luser32 -lshell32 -lpsapi \
  || { echo "  FAIL winhandler.exe did not build (brew install mingw-w64)"; exit 1; }
# GPL-3.0 and the section 7 terms travel with the APK
cp "$HERE/LICENSE" "$APP/main/assets/saipen/LICENSE"
cp "$HERE/NOTICE" "$APP/main/assets/saipen/NOTICE"
cp "$HERE/THIRD_PARTY.md" "$APP/main/assets/saipen/THIRD_PARTY.md"
# every licence text and copyright notice (tools/collect-licenses.py)
python3 -I "$HERE/tools/collect-licenses.py" >/dev/null
cp "$HERE/LICENSES.txt" "$APP/main/assets/saipen/LICENSES.txt"
echo "sdk.dir=$SDK" > "$TREE/local.properties"

OLD=/data/data/com.winlator/
NEW=/data/data/$(sed -n "s/.*applicationId '\\(.*\\)'.*/\\1/p" "$TREE/app/build.gradle")/
if [ ${#OLD} != ${#NEW} ]; then
  echo "  FAIL package path $NEW is not as long as $OLD (the binaries need that)"; exit 1
fi

echo "== no Winlator data path left in code =="
if LC_ALL=C /usr/bin/grep -rn "$OLD" "$APP/main/java" "$APP/main/cpp" "$APP/main/AndroidManifest.xml"; then
  echo "  FAIL these still point at com.winlator's data folder"; exit 1
fi
echo "  ok   none"

echo "== archives: $OLD -> $NEW =="
# Winlator's rootfs is compiled for its own data folder: the path sits in
# ~170 binaries (loader, libc, Wine, Box64) and in the Wine prefix's z:
# link. Rewritten in place, same length (tools/rewrite_tar.py says why).
# The rewritten archives are cached by the pristine archive's hash.
CACHE=$WORK/asset-cache
mkdir -p "$CACHE"
total_hits=0; total_links=0; total_fonts=0; total_other=0; total_fdk=0; total_apps=0
TOOL_KEY=$(shasum -a 256 "$HERE/tools/rewrite_tar.py" | cut -c1-8)
while IFS= read -r t; do
  src=$PRISTINE/app/src/main/assets/$t
  # (counted, not grep -q: an early exit kills zstd and pipefail calls that a failure)
  n=$(zstd -q -dc "$src" | LC_ALL=C /usr/bin/grep -caF "${OLD%/}" || true)
  [ "$n" -gt 0 ] || continue
  key=$(shasum -a 256 "$src" | cut -c1-16)-$(echo "$NEW" | shasum | cut -c1-8)-$TOOL_KEY
  out=$CACHE/$(echo "$t" | tr / _)-$key
  if [ ! -f "$out" ]; then
    stats=$(zstd -q -dc "$src" | python3 -I "$HERE/tools/rewrite_tar.py" "$OLD" "$NEW" 2>&1 >"$out.tar") \
      || { echo "  FAIL $t: $stats"; rm -f "$out.tar"; exit 1; }
    zstd -q -19 -T0 -f "$out.tar" -o "$out" && rm -f "$out.tar"
    echo "$stats" > "$out.stats"
  fi
  read -r entries files hits links fonts other fdk apps < "$out.stats"
  echo "  $t: $hits in $files file(s), $links link(s); left out: $fonts Microsoft font(s), $other other Microsoft file(s), ${fdk:-0} FDK AAC file(s), ${apps:-0} Winlator program file(s)"
  total_hits=$((total_hits + hits)); total_links=$((total_links + links))
  total_fonts=$((total_fonts + fonts)); total_other=$((total_other + other)); total_fdk=$((total_fdk + ${fdk:-0}))
  total_apps=$((total_apps + ${apps:-0}))
  cp "$out" "$APP/main/assets/$t"
done < <(cd "$PRISTINE/app/src/main/assets" && find . -name '*.tzst' | sed 's#^\./##')

# Afterwards nothing may be left: not in contents, not in link targets.
left=0
while IFS= read -r t; do
  f=$APP/main/assets/$t
  n=$(zstd -q -dc "$f" | LC_ALL=C /usr/bin/grep -caF "${OLD%/}" || true)
  if [ "$n" -gt 0 ]; then echo "  FAIL still in $t"; left=1; fi
done < <(cd "$APP/main/assets" && find . -name '*.tzst' | sed 's#^\./##')
[ $left = 0 ] || exit 1
# Winlator 11.2 @ ecd5ab2 has 457 occurrences and 1 link in what we keep (458
# with the FDK AAC plugin, left out since 0.4.13); a big drop means
# the rewrite silently missed something, a rise means Winlator changed.
echo "  ok   $total_hits occurrence(s) and $total_links link(s) rewritten, none left"

echo "== nothing of Microsoft's, no FDK AAC, no unused Winlator program in the APK =="
# Winlator offers Microsoft's DirectX parts, Visual C++ runtimes and Windows
# Media decoder as "Windows components", and ships Microsoft's fonts and
# MFC 4.2 (mfc42.dll) in the Wine prefix. We may not pass them on. SAI draws
# with GDI and needs none of the components (SaiLauncherActivity turns them
# all off); the fonts and other files are left out by rewrite_tar.py. Only
# wincomponents.json stays: Winlator reads it.
rm -f "$APP/main/assets/wincomponents/"*.tzst
if ls "$APP/main/assets/wincomponents/" | /usr/bin/grep -v '^wincomponents.json$'; then
  echo "  FAIL more than wincomponents.json left in assets/wincomponents"; exit 1
fi
# At Winlator 11.2 @ ecd5ab2: 31 fonts (30 in rootfs_patches, Tahoma Bold in
# rootfs) and 1 other file (mfc42.dll in rootfs_patches). Another number
# means Winlator changed: look at what it added or took away.
# And 2 FDK AAC files (libfdk_aac.so and libgstfdkaac.so, in rootfs).
# And 10 files of Winlator's programs (all in rootfs_patches): winaddons.exe,
# 7-Zip's three, TestD3D.exe, GPUInfo.exe, wfm.exe, libcdio.dll, winpreexec,
# and winhandler.exe, which ours replaces.
if [ "$total_fonts" != 31 ] || [ "$total_other" != 1 ] || [ "$total_fdk" != 2 ] || [ "$total_apps" != 10 ]; then
  echo "  FAIL left out $total_fonts Microsoft font(s), $total_other other file(s), $total_fdk FDK AAC file(s) and $total_apps Winlator program file(s), 31, 1, 2 and 10 expected: did Winlator change?"; exit 1
fi
echo "  ok   component archives gone; left out $total_fonts Microsoft font(s), $total_other other file(s), $total_fdk FDK AAC file(s), $total_apps Winlator program file(s)"
# and none anywhere, also in archives with no data path in them
no_forbidden "$APP/main/assets"

cd "$TREE"
if [ "$NO_TESTS" != 1 ]; then
echo "== JVM unit tests =="
./gradlew --no-daemon -q testDebugUnitTest
echo "  ok   JVM unit tests (report: $TREE/app/build/reports/tests/testDebugUnitTest/index.html)"
else
  echo "== tests SKIPPED (SAIPEN_NO_TESTS=1) =="
fi
[ "$MODE" = test ] && exit 0

if [ "$MODE" = release ]; then
  echo "== release APK =="
  [ -f "$RELEASE_KEY" ] || { echo "  FAIL no release key at $RELEASE_KEY"; exit 1; }
  ./gradlew --no-daemon -q assembleRelease
  VERSION=$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -1)
  mkdir -p "$HERE/dist/release"
  OUT=$HERE/dist/release/SAI-Pen-$VERSION.apk
  cp app/build/outputs/apk/release/app-release.apk "$OUT"
  BT=$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)
  # signed with our key, and only with it
  certs=$("$BT/apksigner" verify --print-certs "$OUT" | /usr/bin/grep -c 'certificate SHA-256 digest' || true)
  want=$(sed -n 's/^storeFile=//p' "$RELEASE_KEY" | xargs -I{} "$JAVA_HOME/bin/keytool" -list -keystore {} -storepass "$(sed -n 's/^storePassword=//p' "$RELEASE_KEY")" -v 2>/dev/null \
         | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f' | head -1)
  got=$("$BT/apksigner" verify --print-certs "$OUT" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)
  if [ "$certs" != 1 ] || [ -z "$want" ] || [ "$got" != "$want" ]; then
    echo "  FAIL $OUT is not signed with the release key alone"; mv "$OUT" "$OUT.bad"; exit 1
  fi
  echo "  ok   signed with the release key ($got)"
  if "$BT/aapt2" dump badging "$OUT" | /usr/bin/grep -q application-debuggable; then
    echo "  FAIL $OUT is debuggable"; mv "$OUT" "$OUT.bad"; exit 1
  fi
  echo "  ok   not debuggable"
  "$HERE/build.sh" check-apk "$OUT" || { mv "$OUT" "$OUT.has-forbidden"; exit 1; }
  echo "built $OUT ($(du -h "$OUT" | cut -f1))"
  exit 0
fi

echo "== APK =="
./gradlew --no-daemon -q assembleDebug
VERSION=$(sed -n 's/.*versionName "\(.*\)".*/\1/p' app/build.gradle | head -1)
mkdir -p "$HERE/dist"
OUT=$HERE/dist/SAI-Pen-$VERSION.apk
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"
# the same check once more, on what is really shipped
"$HERE/build.sh" check-apk "$OUT" || { mv "$OUT" "$OUT.has-forbidden"; exit 1; }
echo "built $OUT ($(du -h "$OUT" | cut -f1))"
