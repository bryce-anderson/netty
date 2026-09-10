#!/usr/bin/env bash
# Runs inside the manylinux2014 container: builds the FULL Netty reactor (what
# docker-compose.centos-7.yaml runs), to validate that this image can replace
# Dockerfile.centos7 wholesale — i.e. flush out any build dependency the old
# image provided that this one is missing. Invoked via `dev-build.sh full`.
set -euxo pipefail

: "${LIBCLANG_PATH:?LIBCLANG_PATH not set — check /etc/profile.d/libclang.sh in the image}"
echo "LIBCLANG_PATH=$LIBCLANG_PATH"
echo "BINDGEN_EXTRA_CLANG_ARGS=${BINDGEN_EXTRA_CLANG_ARGS:-<unset>}"

export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}"

# Skip tests by default: this run is to surface missing *build* deps across the
# whole reactor (incl. the native modules), not to run the suite. Set
# SKIP_TESTS=false to also run tests once the build is clean.
SKIP_TESTS="${SKIP_TESTS:-true}"

./mvnw -B -ntp clean package \
  "-DskipTests=$SKIP_TESTS" \
  -Dcheckstyle.skip -Dforbiddenapis.skip=true -Drevapi.skip=true
