#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
termux=/data/data/com.termux/files
state="${CODEX_MOBILE_STATE:-$HOME/.local/state/codex-mobile}"
mkdir -p .build/deps .build/classes .build/dex dist "$state"
umask 077
fetch() {
  if [[ ! -s "$2" ]]; then
    curl --fail --location --silent --show-error --retry 2 "$1" -o "$2.part"
    mv "$2.part" "$2"
  fi
}
sdk=.build/deps/android28.jar
r8=.build/deps/r8.jar
fetch https://repo.maven.apache.org/maven2/org/robolectric/android-all/9-robolectric-4913185-2/android-all-9-robolectric-4913185-2.jar "$sdk"
fetch https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar "$r8"
fetch https://cdn.jsdelivr.net/npm/markdown-it@14.1.0/dist/markdown-it.min.js web/markdown-it.min.js
export PATH="$termux/usr/bin:$PATH"
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
launcher="$termux/home/.local/share/codex-mobile/start"
cat > .build/LocalConfig.java <<JAVA
package local.codex.mobile;
final class LocalConfig {
    static final String LAUNCHER = "$launcher";
}
JAVA
aapt2 compile --dir android/res -o .build/resources.zip
aapt2 link -I "$sdk" --manifest android/AndroidManifest.xml -o .build/unsigned.apk .build/resources.zip
javac --release 8 -classpath "$sdk" -d .build/classes android/MainActivity.java .build/LocalConfig.java
find .build/classes -name '*.class' -print0 | xargs -0 java -cp "$r8" com.android.tools.r8.D8 --min-api 23 --lib "$sdk" --lib "$JAVA_HOME" --output .build/dex
jar uf .build/unsigned.apk -C .build/dex classes.dex
if [[ ! -f "$state/signing.p12" ]]; then
  keytool -genkeypair -keystore "$state/signing.p12" -alias codex-mobile -storepass android \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Codex Mobile Local'
fi
jarsigner -keystore "$state/signing.p12" -storepass android -sigalg SHA256withRSA -digestalg SHA-256 \
  -signedjar dist/Codex-Mobile.apk .build/unsigned.apk codex-mobile
echo "APK pronto em $PWD/dist/Codex-Mobile.apk"
