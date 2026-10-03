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
# IPv4 sockets: an IPv6-mapped 127.0.0.1 bind is forwarded by WSL to Windows ::1 only, not 127.0.0.1.
export KAFKA_OPTS="-Djava.net.preferIPv4Stack=true"
mkdir -p "$STATE/data" "$LOG_DIR"

stop_broker() {  # clean shutdown (SIGTERM), SIGKILL after 60 s
  pgrep -f "kafka.Kafka $CFG" >/dev/null || return 0
  echo "stopping broker"; pkill -TERM -f "kafka.Kafka $CFG" || true
  for i in $(seq 1 60); do pgrep -f "kafka.Kafka $CFG" >/dev/null || return 0; sleep 1; done
  pkill -KILL -f "kafka.Kafka $CFG" || true
}
case "${1:-}" in
  --stop) stop_broker; exit 0 ;;
  --status)
    pid=$(pgrep -f "kafka.Kafka $CFG" | head -1 || true)
    if [ -z "$pid" ]; then echo "broker (WSL): not running"; exit 0; fi
    ps -o rss=,pcpu=,etime= -p "$pid" | awk -v p="$pid" '{printf "broker (WSL): pid %s, RSS %.0f MB, avg CPU %s%% since start, up %s\n", p, $1/1024, $2, $3}'
    t1=$(awk '{print $14+$15}' /proc/$pid/stat); sleep 10; t2=$(awk '{print $14+$15}' /proc/$pid/stat)
    awk -v a="$t1" -v b="$t2" -v hz="$(getconf CLK_TCK)" 'BEGIN{printf "broker (WSL): CPU over last 10 s %.2f%% of one core\n", (b-a)/hz/10*100}'
    exit 0 ;;
esac

# A broker left over from a killed wsl.exe client: stop it cleanly first.
[ "${1:-}" = "--format-only" ] || stop_broker

if [ ! -f "$STATE/data/meta.properties" ]; then
  id=$("$DIST/bin/kafka-storage.sh" random-uuid)
  "$DIST/bin/kafka-storage.sh" format -t "$id" -c "$CFG" --standalone
fi

if [ "${1:-}" = "--format-only" ]; then exit 0; fi
exec "$DIST/bin/kafka-server-start.sh" "$CFG"
