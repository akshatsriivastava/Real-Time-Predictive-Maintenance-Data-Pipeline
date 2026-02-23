# Source this file to put the project JDK on PATH for the current shell:
#   source env.sh
# Then you can use ./mvnw directly (e.g. ./mvnw clean compile).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export JAVA_HOME="${SCRIPT_DIR}/.jdk/jdk-17.0.18+8/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"
