#!/usr/bin/env bash
# Run a build inside the already-running dev container (see dev-start.sh).
# Reuses the persisted BoringSSL/quiche/cargo caches, so repeated runs only redo
# what changed.
#
#   docker/dev-build.sh              # quic-only build in the arm64 container
#   ARCH=amd64 docker/dev-build.sh   # quic-only build in the x86_64 container
#   docker/dev-build.sh full         # FULL reactor build (validates centos7 replacement)
#   docker/dev-build.sh shell        # interactive shell
set -euo pipefail

# Container CLI: docker by default; override with DOCKER=<cli> if needed.
DOCKER="${DOCKER:-docker}"

ARCH="${ARCH:-arm64}"
NAME="${NAME:-netty-quic-dev-$ARCH}"

if ! $DOCKER inspect "$NAME" >/dev/null 2>&1; then
  echo "Container $NAME is not running. Start it first: ARCH=$ARCH docker/dev-start.sh" >&2
  exit 1
fi

# `docker exec` does not forward host env vars into the container, so pass through
# the build knobs explicitly (only when set, so container-side defaults apply).
env_args=()
for v in SKIP_TESTS CARGO_BUILD_JOBS; do
  [ -n "${!v:-}" ] && env_args+=(-e "$v=${!v}")
done

case "${1:-}" in
  shell) exec $DOCKER exec -it ${env_args[@]+"${env_args[@]}"} "$NAME" bash -l ;;
  full)  exec $DOCKER exec -it ${env_args[@]+"${env_args[@]}"} "$NAME" bash -l /work/docker/build-full-in-container.sh ;;
  *)     exec $DOCKER exec -it ${env_args[@]+"${env_args[@]}"} "$NAME" bash -l /work/docker/build-quic-in-container.sh ;;
esac
