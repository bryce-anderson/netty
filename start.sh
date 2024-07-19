#!/usr/bin/env bash
set -ex

name="netty-ubuntu-dev"
image="netty-ubuntu-dev"
version="latest"
command="bash"
location="$(pwd)"

vessel run \
            --name="$name" \
            --cpus="10" \
            --memory=8g \
            -v "$location":/workspace \
            -v ~/.m2:/root/.m2 \
            -w /workspace \
            -e JAVA_HOME="/usr/lib/jvm/java-21-openjdk-arm64" \
            --rm \
            --tty \
            --interactive \
          "$image":"$version" \
          "$command"
