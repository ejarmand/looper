# Source this to point the build at the project-local toolchain in .toolchain/.
# Nothing is installed globally; delete .toolchain/ to remove everything.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="$ROOT/.toolchain"
export JAVA_HOME="$TC/jdk"
export ANDROID_HOME="$TC/android-sdk"
# Keeps ~/.android (debug keystore, AGP's opted-out analytics stub) inside .toolchain too.
export ANDROID_SDK_HOME="$TC/android-home"
export GRADLE_USER_HOME="$TC/gradle-home"
export SDKMANAGER_OPTS="-Djava.util.prefs.userRoot=$TC/java-prefs"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
