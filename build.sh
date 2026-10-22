#!/bin/sh
# Builds out/books.apk. Needs Java 17 and the Android SDK (platform 34, build-tools 34).
# Same toolchain as tv-detox (github.com/Ansub/tv-detox): no Gradle, no libraries.
set -e
cd "$(dirname "$0")"

[ -n "$JAVA_HOME" ] || for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do [ -d "$j" ] && JAVA_HOME=$j && break; done
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$SDK" ] || for d in "$HOME/Library/Android/sdk" /opt/homebrew/share/android-commandlinetools "$HOME/Android/Sdk"; do [ -d "$d" ] && SDK=$d && break; done
BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-34/android.jar
[ -f "$JAR" ] && [ -d "$BT" ] || { echo "Android SDK not found. Install platforms;android-34 and build-tools;34.0.0." >&2; exit 1; }

rm -rf out; mkdir -p out/classes out/dex out/gen
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34 \
  --java out/gen out/res.zip
javac -Xlint:all -Xlint:-options -Xlint:-deprecation -Werror -source 1.8 -target 1.8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d out/classes $(find src out/gen -name "*.java")
"$BT/d8" --min-api 21 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk; (cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# A signing key is made on first build. Keep key.jks: updates must be signed with the same key.
KS=${KEYSTORE:-key.jks}; PASS=${KEYSTORE_PASS:-changeit}
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias books \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=books" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --out out/books.apk out/aligned.apk
echo "Built out/books.apk ($(wc -c < out/books.apk | tr -d ' ') bytes)"
