#!/usr/bin/env bash
# Runs the FacetIndex CLI from build/install (./gradlew installDist first).
# FI_HEAP sets the heap (default 6g). Data paths come from FACETINDEX_DATA.
here="$(cd "$(dirname "$0")/.." && pwd)"
command -v cygpath >/dev/null && here="$(cygpath -m "$here")"
JAVA_HOME="${JAVA_HOME:-/c/SullaPortal/data/facetindex/jdk/jdk-21.0.12.1+1}"
exec "$JAVA_HOME/bin/java" -Xmx"${FI_HEAP:-6g}" ${FI_JVM_OPTS:-} --enable-preview --enable-native-access=ALL-UNNAMED \
  --add-modules jdk.incubator.vector -cp "$here/build/install/facetindex/lib/*" facetindex.cli.MainKt "$@"
