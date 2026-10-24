#!/bin/bash

# Runs Maven inside the official image, so building and testing needs nothing installed locally beyond
# docker.
#
#   ./scripts/mvn.sh verify            # build and test every module; the plugin is dist/target/VPNDetection-*.jar
#   ./scripts/mvn.sh -q -pl core test
#
# The dependency cache is mounted from the host's home rather than the working tree, so it is neither
# downloaded again on every run nor committed.

set -euo pipefail

cd "$(dirname "$0")/.."

MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.11-eclipse-temurin-21}"
M2_DIR="${M2_DIR:-${HOME}/.m2}"

mkdir -p "$M2_DIR"

docker run --rm \
    -v "$PWD:/w" \
    -v "$M2_DIR:/root/.m2" \
    -w /w \
    -e MAVEN_CONFIG=/root/.m2 \
    "$MAVEN_IMAGE" mvn -B -Duser.home=/root "$@"
