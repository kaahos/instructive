#!/usr/bin/env bash
# Runs inside the container started by test-linux.sh: copies the read-only checkout at
# /src to /work without build outputs, runs Gradle there, summarises the tests and copies
# out what other steps need: native libraries to /out and test reports to /reports, if
# those are mounted. Reports are copied even when the build fails.
set -euo pipefail
rsync -a --exclude build --exclude .gradle --exclude .idea /src/ /work/
cd /work
echo "$(uname -m), $(ldd --version 2>&1 | head -1), $(java -version 2>&1 | head -1)"
copy_reports() {
    if [[ -d /reports ]]; then
        find /work -type d \( -path '*/build/reports/tests' -o -path '*/build/test-results' \) | while read -r dir; do
            mkdir -p "/reports/${dir#/work/}" && cp -R "$dir/." "/reports/${dir#/work/}/"
        done
    fi
}
trap copy_reports EXIT
./gradlew --no-daemon --no-watch-fs --console=plain "$@"
results=$(find /work -path '*/test-results/*/*.xml' -exec cat {} + 2>/dev/null || true)
echo "tests: $(grep -c '<testcase' <<< "$results" || true), skipped: $(grep -c '<skipped' <<< "$results" || true)"
grep -B1 '<skipped' <<< "$results" | grep -o 'testcase name="[^"]*" classname="[^"]*"' \
    | sed -E 's/testcase name="([^"]*)" classname="([^"]*)"/  skipped: \2.\1/' || true
if [[ -d /out && -d agent/build/native-libs/META-INF ]]; then
    cp -R agent/build/native-libs/META-INF /out/
    find /out -name '*.so' | sed 's/^/  native library: /'
fi
