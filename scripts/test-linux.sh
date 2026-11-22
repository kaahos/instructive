#!/usr/bin/env bash
# Builds and tests the project on Linux in Docker, for x86-64, AArch64 or both:
#
#   scripts/test-linux.sh                           # both architectures, ./gradlew build
#   scripts/test-linux.sh amd64                     # linux/amd64 only (emulated on Apple Silicon)
#   scripts/test-linux.sh arm64 :agent-test:test    # a different Gradle task
#   DISTRO=alpine JDK=17 scripts/test-linux.sh arm64
#
# DISTRO is ubuntu (glibc, the default) or alpine (musl); JDK is 17, 21 or 25 (the
# default). If OUT is set, the native library built for each platform is copied to
# $OUT/META-INF/native-libs/<platform>/; if REPORTS is set, test reports are copied there.
#
# The repository is mounted read-only and copied inside the container without its
# build outputs, so Linux and host builds never mix. Gradle's home (its caches, and the
# Gradle distribution) persists in a Docker volume per distribution and architecture,
# or, if GRADLE_CACHE is set, in the host directory $GRADLE_CACHE/<distro>-<arch>, which
# CI saves and restores between runs so the jobs don't all download their dependencies
# again. CI runs this same script.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
# Docker Desktop's CLI and credential helpers may not be on PATH.
for bin in "$HOME/.docker/bin" /Applications/Docker.app/Contents/Resources/bin; do
    [[ -d "$bin" && ":$PATH:" != *":$bin:"* ]] && PATH="$PATH:$bin"
done
docker="${DOCKER:-docker}"
distro="${DISTRO:-ubuntu}"
jdk="${JDK:-25}"

case "${1:-all}" in
    all) architectures=(amd64 arm64) ;;
    amd64 | arm64) architectures=("$1") ;;
    *) echo "usage: $0 [all|amd64|arm64] [gradle task...]" >&2; exit 2 ;;
esac
shift || true
tasks=("${@:-build}")

mounts=()
if [[ -n "${OUT:-}" ]]; then
    mkdir -p "$OUT"
    mounts+=(-v "$(cd "$OUT" && pwd):/out")
fi
if [[ -n "${REPORTS:-}" ]]; then
    mkdir -p "$REPORTS"
    mounts+=(-v "$(cd "$REPORTS" && pwd):/reports")
fi

status=0
for arch in "${architectures[@]}"; do
    name="$distro-jdk$jdk linux/$arch"
    image="instructive-linux-build:$distro-$jdk-$arch"
    echo "=== $name: building image"
    "$docker" build --quiet --platform "linux/$arch" --target "$distro" --build-arg "JDK=$jdk" \
        -t "$image" -f "$root/docker/linux-build.Dockerfile" "$root/docker" > /dev/null
    if [[ -n "${GRADLE_CACHE:-}" ]]; then
        mkdir -p "$GRADLE_CACHE/$distro-$arch"
        gradle_home="$(cd "$GRADLE_CACHE/$distro-$arch" && pwd)"
    else
        gradle_home="instructive-gradle-$distro-$arch"
    fi
    echo "=== $name: ./gradlew ${tasks[*]}"
    if ! "$docker" run --rm --platform "linux/$arch" \
        -v "$root:/src:ro" \
        -v "$gradle_home:/root/.gradle" \
        ${mounts[@]+"${mounts[@]}"} \
        "$image" /src/scripts/linux-build-in-container.sh "${tasks[@]}"; then
        echo "=== $name: FAILED"
        status=1
    else
        echo "=== $name: passed"
    fi
done
exit $status
