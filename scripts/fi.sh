#!/usr/bin/env bash
# Runs the FacetIndex CLI (./gradlew installDist first). The jars are copied to a content-stamped
# directory under $FACETINDEX_DATA/libs so that rebuilding while a long job runs never touches the
# jars that job has open (Windows locks them).
# FI_HEAP sets the heap (default 6g). Data paths come from FACETINDEX_DATA.
here="$(cd "$(dirname "$0")/.." && pwd)"
data="${FACETINDEX_DATA:-/c/SullaPortal/data/facetindex}"
lib="$here/build/install/facetindex/lib"
# The project jar comes from build/libs (./gradlew jar), the dependencies from installDist.
jar="$(ls -t "$here"/build/libs/facetindex-*.jar 2>/dev/null | head -1)"
[ -n "$jar" ] || jar="$(ls "$lib"/facetindex-*.jar)"
stamp="$(cat "$jar" | md5sum | cut -c1-12)"
run="$data/libs/$stamp"
if [ ! -d "$run" ]; then
  mkdir -p "$run.tmp" && cp "$lib"/*.jar "$run.tmp"/ && rm -f "$run.tmp"/facetindex-*.jar && cp "$jar" "$run.tmp"/ && mv "$run.tmp" "$run"
fi
command -v cygpath >/dev/null && run="$(cygpath -m "$run")"
JAVA_HOME="${JAVA_HOME:-/c/SullaPortal/data/facetindex/jdk/jdk-21.0.12.1+1}"
exec "$JAVA_HOME/bin/java" -Xmx"${FI_HEAP:-6g}" ${FI_JVM_OPTS:-} --enable-preview --enable-native-access=ALL-UNNAMED \
  --add-modules jdk.incubator.vector -cp "$run/*" facetindex.cli.MainKt "$@"
