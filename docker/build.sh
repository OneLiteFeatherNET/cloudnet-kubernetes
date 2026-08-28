#!/usr/bin/env sh
# Assembles the service base image from a CloudNet node installation.
#
# Usage: docker/build.sh <path-to-cloudnet> [tag]
set -eu

CLOUDNET_HOME=${1:?"usage: build.sh <path-to-cloudnet> [tag]"}
TAG=${2:-ghcr.io/onelitefeathernet/cloudnet-service:4.0.0-RC17}
HERE=$(cd "$(dirname "$0")" && pwd)

if [ ! -d "$CLOUDNET_HOME/launcher/libs" ]; then
  echo "no launcher/libs under $CLOUDNET_HOME - start the node once so it unpacks" >&2
  exit 1
fi

if [ ! -f "$CLOUDNET_HOME/temp/caches/wrapper.jar" ]; then
  echo "no temp/caches/wrapper.jar under $CLOUDNET_HOME - start a service once" >&2
  exit 1
fi

rm -rf "$HERE/libs" "$HERE/wrapper.jar"
cp -r "$CLOUDNET_HOME/launcher/libs" "$HERE/libs"
cp "$CLOUDNET_HOME/temp/caches/wrapper.jar" "$HERE/wrapper.jar"

docker build -t "$TAG" "$HERE"

rm -rf "$HERE/libs" "$HERE/wrapper.jar"
echo "built $TAG"
