#!/usr/bin/env bash
# Builds VocaBoost and packages it with jpackage for the OS this runs on (Linux or macOS): an app
# image by default, or an installer with --type deb|rpm (Linux) or dmg|pkg (macOS). Like
# scripts/package-windows.ps1, it builds with the Maven Wrapper (tests included unless --skip-tests),
# trims the Java runtime with jdeps and jlink, and takes the version from pom.xml.
#
# Usage: scripts/package.sh [--type app-image|deb|rpm|dmg|pkg] [--skip-tests]
# Needs a JDK 17 or newer (JAVA_HOME, or its bin folder on PATH); the wrapper downloads Maven.
set -euo pipefail

usage() {
    sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
}

die() {
    echo "error: $*" >&2
    exit 1
}

step() {
    printf '==> %s\n' "$*"
}

type=app-image
skip_tests=false
while [ $# -gt 0 ]; do
    case "$1" in
        --type)
            [ $# -ge 2 ] || die "--type needs a value"
            type=$2
            shift 2
            ;;
        --type=*)
            type=${1#--type=}
            shift
            ;;
        --skip-tests)
            skip_tests=true
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "unknown option: $1"
            ;;
    esac
done

project_root=$(cd "$(dirname "$0")/.." && pwd)
# The app icon (packaging/icons/make-icons.sh renders both from packaging/icons/vocaboost.svg).
case "$(uname -s)" in
    Linux) os=linux types="app-image deb rpm" icon=$project_root/src/main/resources/icons/vocaboost-512.png ;;
    Darwin) os=macos types="app-image dmg pkg" icon=$project_root/packaging/icons/vocaboost.icns ;;
    *) die "use scripts/package-windows.ps1 on Windows" ;;
esac
case " $types " in
    *" $type "*) ;;
    *) die "--type $type is not available on $os (use one of: $types)" ;;
esac

target=$project_root/target
input_dir=$target/jpackage-input
runtime_dir=$target/jpackage-runtime
output_dir=$target/dist
app_name=VocaBoost
main_class=com.vocabtrainer.app.VocabTrainerLauncher
# Needed at run time but invisible to jdeps: TLS key exchange for the online dictionary and the AI
# provider (jdk.crypto.ec; part of java.base from JDK 22 on), GBK/GB18030 CSV files (jdk.charsets)
# and Chinese date and number formats (jdk.localedata, trimmed to $locales).
extra_modules="jdk.crypto.ec jdk.charsets jdk.localedata"
locales=en,zh

# The same JDK as the Maven Wrapper, which also prefers JAVA_HOME.
jdk_tool() {
    if [ -n "${JAVA_HOME:-}" ]; then
        [ -x "$JAVA_HOME/bin/$1" ] ||
            die "JAVA_HOME points to $JAVA_HOME, but $JAVA_HOME/bin/$1 does not exist. Point JAVA_HOME to a JDK 17 or newer (jdeps, jlink and jpackage are not part of a JRE)."
        echo "$JAVA_HOME/bin/$1"
    else
        command -v "$1" ||
            die "$1 was not found. Install a JDK 17 or newer (jdeps, jlink and jpackage are not part of a JRE) and set JAVA_HOME or add its bin folder to PATH."
    fi
}

# Check the tools first, so a missing one does not show up only after the build and the tests.
[ -f "$icon" ] || die "the app icon $icon is missing"
java=$(jdk_tool java)
jdeps=$(jdk_tool jdeps)
jlink=$(jdk_tool jlink)
jpackage=$(jdk_tool jpackage)

mvn_args=(clean package)
if [ "$skip_tests" = true ]; then
    mvn_args+=(-DskipTests)
fi
if [ -n "${VOCABOOST_MAVEN_REPO:-}" ]; then
    mvn_args=("-Dmaven.repo.local=$VOCABOOST_MAVEN_REPO" "${mvn_args[@]}")
fi
step "Building and testing with Maven"
(cd "$project_root" && ./mvnw "${mvn_args[@]}") || die "the Maven build failed"

# Written by the jar build from pom.xml, so the version is never repeated here.
pom_properties=$target/maven-archiver/pom.properties
[ -f "$pom_properties" ] || die "$pom_properties is missing; did the jar build run?"
version=$(sed -n 's/^version=//p' "$pom_properties" | tr -d '\r')
artifact_id=$(sed -n 's/^artifactId=//p' "$pom_properties" | tr -d '\r')
jar_name=$artifact_id-$version.jar
# jpackage wants a plain numeric version, so 1.2.0-SNAPSHOT is packaged as 1.2.0.
app_version=${version%%-*}
[[ $app_version =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] ||
    die "the pom version '$version' does not start with a numeric version that jpackage accepts, like 1.2.0"

# The app jar and its runtime jars (copied to target/lib by the package phase).
rm -rf "$input_dir" "$runtime_dir"
mkdir -p "$input_dir"
cp "$target/$jar_name" "$target"/lib/*.jar "$input_dir"/

step "Finding the Java modules the app uses (jdeps)"
detected=$("$jdeps" --print-module-deps --ignore-missing-deps --multi-release 17 "$input_dir"/*.jar |
    grep -E '^[A-Za-z0-9_.]+(,[A-Za-z0-9_.]+)*$' | tail -n 1) || die "jdeps did not print a module list"
available=$("$java" --list-modules | sed 's/@.*//') || die "java --list-modules failed"
modules=$detected
for module in $extra_modules; do
    case ",$modules," in
        *",$module,"*) ;;
        *) if grep -qx "$module" <<<"$available"; then modules=$modules,$module; fi ;;
    esac
done

jdk_major=$("$jlink" --version | sed -n '1s/[^0-9].*//p')
compress=--compress=2
if [ "${jdk_major:-0}" -ge 21 ]; then
    compress=--compress=zip-6
fi
jlink_args=(--add-modules "$modules" --strip-debug --no-man-pages --no-header-files --strip-native-commands
    "$compress" --output "$runtime_dir")
case ",$modules," in
    *,jdk.localedata,*) jlink_args+=("--include-locales=$locales") ;;
esac
step "Building a trimmed Java runtime (jlink)"
"$jlink" "${jlink_args[@]}" || die "jlink failed"

package_args=(--type "$type" --name "$app_name" --app-version "$app_version" --input "$input_dir"
    --main-jar "$jar_name" --main-class "$main_class" --runtime-image "$runtime_dir" --dest "$output_dir"
    --vendor VocaBoost --description "JavaFX and SQLite vocabulary trainer" --icon "$icon")
if [ "$type" = deb ] || [ "$type" = rpm ]; then
    package_args+=(--linux-shortcut)
fi
step "Packaging the $type (jpackage)"
mkdir -p "$output_dir"
rm -rf "${output_dir:?}/$app_name" "${output_dir:?}/$app_name.app"
"$jpackage" "${package_args[@]}" || die "jpackage failed"

case "$type:$os" in
    app-image:linux) result=$output_dir/$app_name launcher=$output_dir/$app_name/bin/$app_name ;;
    app-image:macos) result=$output_dir/$app_name.app launcher=$output_dir/$app_name.app/Contents/MacOS/$app_name ;;
    *) result=$(find "$output_dir" -maxdepth 1 -type f -name "*.$type" | head -n 1) launcher=$result ;;
esac
[ -n "$launcher" ] && [ -e "$launcher" ] || die "jpackage finished, but the $type is missing from $output_dir"

echo "Packaged $app_name $version: $result ($(du -sh "$result" | cut -f1))"
echo "Java modules: $modules"
if [ "$type" = app-image ]; then
    echo "Run: $launcher"
fi
