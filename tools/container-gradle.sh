#!/usr/bin/env bash
# Runs Gradle inside the Android SDK container, so the project builds on a
# machine with nothing but Docker installed.
#
#   docker run -d --name connect-build \
#     -v connect-gradle:/root/.gradle -v connect-work:/work -v "$PWD:/src" \
#     ghcr.io/cirruslabs/android-sdk:35 sleep infinity
#   docker exec connect-build bash /src/tools/container-gradle.sh :domain:test
#
# Sources are copied from the bind mount into a volume first: Gradle on a
# Windows bind mount is several times slower than on a native volume.
set -euo pipefail

mkdir -p /work
# Drop sources that were deleted on the host, but keep build outputs.
find /work -mindepth 1 -type f \
  -not -path '*/build/*' -not -path '/work/.gradle/*' -not -path '/work/.kotlin/*' -delete 2>/dev/null || true
(cd /src && tar cf - --exclude=./.git --exclude=build --exclude=./.gradle --exclude=./.kotlin --exclude=./out .) \
  | (cd /work && tar xf -)

cd /work
chmod +x gradlew
set +e
./gradlew "$@"
status=$?
set -e

# Hand reports back to the host.
rm -rf /src/out/reports && mkdir -p /src/out/reports
find . -path '*/build/test-results/*' -name '*.xml' | while read -r f; do
  mkdir -p "/src/out/reports/$(dirname "$f")" && cp "$f" "/src/out/reports/$f"
done
[ -d build/benchmark ] && cp -r build/benchmark /src/out/ || true
exit $status
