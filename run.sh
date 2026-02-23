#!/bin/sh
# Use the project-local JDK (Eclipse Temurin 17) and run Maven.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${SCRIPT_DIR}/.jdk/jdk-17.0.18+8/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"
exec "${SCRIPT_DIR}/mvnw" "$@"
