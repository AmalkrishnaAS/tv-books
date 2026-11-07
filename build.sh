#!/bin/sh
# Builds and signs out/books.apk straight from the Android SDK's command-line tools:
# no Gradle and no libraries. Needs Java 17 plus SDK platform 34 and build-tools 34.0.0.
set -e
cd "$(dirname "$0")"

first_dir() { for d in "$@"; do [ -d "$d" ] && { echo "$d"; return; }; done; return 0; }

JAVA_HOME=${JAVA_HOME:-$(first_dir /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64)}
[ -n "$JAVA_HOME" ] && PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(first_dir "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools)}}
TOOLS=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
if [ ! -f "$PLATFORM" ] || [ ! -d "$TOOLS" ]; then
  echo "Can't find the Android SDK: install platforms;android-34 and build-tools;34.0.0" >&2
  exit 1
fi

rm -rf out
mkdir -p out/gen out/classes out/dex

# 1. Resources and manifest, plus the generated R class.
"$TOOLS/aapt2" compile --dir res -o out/res.zip
"$TOOLS/aapt2" link out/res.zip -o out/resources.apk -I "$PLATFORM" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 --java out/gen

# 2. Java 8 bytecode (lambdas desugared by d8), every warning an error, then dex.
javac -source 1.8 -target 1.8 -bootclasspath "$PLATFORM:$TOOLS/core-lambda-stubs.jar" \
  -Xlint:all -Xlint:-options -Xlint:-deprecation -Werror \
  -d out/classes $(find src out/gen -name '*.java')
"$TOOLS/d8" --release --min-api 21 --lib "$PLATFORM" --output out/dex $(find out/classes -name '*.class')

# 3. Package, align and sign. The key is created on the first build; keep key.jks, because
#    Android only installs an update signed with the same key. UNSIGNED=1 stops before signing
#    (out/books-unsigned.apk), for stores such as F-Droid that sign with their own key.
cp out/resources.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 out/unsigned.apk out/aligned.apk
if [ "${UNSIGNED:-}" = 1 ]; then
  cp out/aligned.apk out/books-unsigned.apk
  echo "Built out/books-unsigned.apk ($(wc -c < out/books-unsigned.apk | tr -d ' ') bytes, not signed)"
  exit 0
fi
KEYSTORE=${KEYSTORE:-key.jks}
# The key's password: KEYSTORE_PASS, else the first line of .keystore-pass (git-ignored), else a
# default for a newly made key.
[ -z "${KEYSTORE_PASS:-}" ] && [ -f .keystore-pass ] && KEYSTORE_PASS=$(head -n 1 .keystore-pass)
KEYSTORE_PASS=${KEYSTORE_PASS:-changeit}
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -alias books -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=books" >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --out out/books.apk out/aligned.apk
echo "Built out/books.apk ($(wc -c < out/books.apk | tr -d ' ') bytes)"
