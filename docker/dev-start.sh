#!/usr/bin/env bash
# Start a long-lived dev container for iterating on the codec-native-quic build.
# Build outputs live in codec-native-quic/target (on the mounted repo), so the
# BoringSSL/quiche/cargo caches persist across `dev-build.sh` runs and even
# across container restarts. Rebuild the image only when the Dockerfile changes.
#
#   docker/dev-start.sh                 # arm64 (native on Apple Silicon)
#   ARCH=amd64 docker/dev-start.sh      # x86_64 (emulated on Apple Silicon)
#   docker/dev-build.sh [ARCH=...]      # run the quic build inside it (repeatable)
#   docker/dev-build.sh shell           # interactive shell in the arm64 container
#   docker stop netty-quic-dev-<arch>   # tear down when done
#
# Container CLI defaults to `docker`; override with DOCKER=<cli>. ARCH is a Docker
# platform arch: arm64 (default) or amd64. riscv64 is not built here (no
# manylinux2014 image; postdates glibc 2.17) — keep the custom cross path.
set -euxo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Container CLI: docker by default; override with DOCKER=<cli> if needed.
DOCKER="${DOCKER:-docker}"

ARCH="${ARCH:-arm64}"
case "$ARCH" in
  arm64) BASE_IMAGE=quay.io/pypa/manylinux2014_aarch64 ;;
  amd64) BASE_IMAGE=quay.io/pypa/manylinux2014_x86_64 ;;
  *) echo "unsupported ARCH: $ARCH (use arm64 or amd64)" >&2; exit 1 ;;
esac

NAME="${NAME:-netty-quic-dev-$ARCH}"
TAG="netty-quic-ml2014-$ARCH"
CPUS="${CPUS:-4}"
MEM="${MEM:-8G}"

$DOCKER build --platform "linux/$ARCH" \
  --build-arg "BASE_IMAGE=$BASE_IMAGE" \
  -f docker/Dockerfile.manylinux2014 \
  -t "$TAG" docker/

if $DOCKER inspect "$NAME" >/dev/null 2>&1; then
  $DOCKER start "$NAME" >/dev/null 2>&1 || true
  echo "Container $NAME already exists; reusing it. Run: ARCH=$ARCH docker/dev-build.sh"
else
  $DOCKER run -d --name "$NAME" --platform "linux/$ARCH" \
    --cpus "$CPUS" --memory "$MEM" \
    -v "$REPO_ROOT":/work -w /work \
    "$TAG" \
    sleep infinity
  echo "Started $NAME. Run: ARCH=$ARCH docker/dev-build.sh"
fi
