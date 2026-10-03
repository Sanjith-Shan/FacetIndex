#!/usr/bin/env bash
# Foreground Kafka broker inside WSL (Ubuntu-24.04), started by the SullaPortal service facetindex/kafka
# via scripts/kafka_service.ps1. Dist + JDK live on /mnt/c; data and logs live on the WSL ext4 disk
# because NTFS (native or via /mnt/c) refuses to rename a partition dir with open files (topic delete).
set -euo pipefail
K=/mnt/c/SullaPortal/data/facetindex/kafka
CFG=$K/config/server-wsl.properties
DIST=$K/kafka_2.13-4.3.1
STATE=$HOME/facetindex-kafka
export JAVA_HOME=$K/linux-jdk/jdk-21.0.12.1+1
export KAFKA_HEAP_OPTS="-Xmx1g -Xms1g"
export LOG_DIR=$STATE/logs
mkdir -p "$STATE/data" "$LOG_DIR"

# A broker left over from a killed wsl.exe client: stop it cleanly first.
if pgrep -f "kafka.Kafka $CFG" >/dev/null; then
  echo "stopping leftover broker"; pkill -TERM -f "kafka.Kafka $CFG" || true
  for i in $(seq 1 60); do pgrep -f "kafka.Kafka $CFG" >/dev/null || break; sleep 1; done
  pkill -KILL -f "kafka.Kafka $CFG" || true
fi

if [ ! -f "$STATE/data/meta.properties" ]; then
  id=$("$DIST/bin/kafka-storage.sh" random-uuid)
  "$DIST/bin/kafka-storage.sh" format -t "$id" -c "$CFG" --standalone
fi

if [ "${1:-}" = "--format-only" ]; then exit 0; fi
exec "$DIST/bin/kafka-server-start.sh" "$CFG"
