# Kafka for FacetIndex (mini PC)

Apache Kafka 4.3.1 (Scala 2.13 build, kafka_2.13-4.3.1.tgz, SHA-512 checked against the Apache .sha512 file), running single-node in KRaft combined mode (process.roles=broker,controller, node.id=1, no ZooKeeper). Clients on Windows use bootstrap server 127.0.0.1:19092.

## Native Windows vs WSL

Native Windows was tried first and failed. The broker starts and serves produce/consume fine, but deleting a topic makes Kafka rename the partition directory (catalog-events-0 to catalog-events-0.<id>-delete), and NTFS refuses to rename a directory while files inside it are open or memory mapped (AccessDeniedException). Kafka then marks the only log dir offline and exits, and on every restart it fails the same way renaming the stray directory, so it crash-loops until the data dir is wiped. Since reset-topic is part of the workflow, native was dropped.

The broker now runs in WSL Ubuntu-24.04 (capped at 2 vCPU / 6 GB by the box .wslconfig, which was not changed). The Kafka dist and a Temurin 21 Linux JDK live under C:\SullaPortal\data\facetindex\kafka\ (seen from WSL as /mnt/c/...). The data and log dirs are on the WSL ext4 disk at /home/sulla_claw/facetindex-kafka/{data,logs}, not on /mnt/c: the same rename test fails through /mnt/c because it is still NTFS underneath. The Windows client tools (kafka-topics.bat, perf tests) run with the project JDK at C:\SullaPortal\data\facetindex\jdk\jdk-21.0.12.1+1.

The broker forces IPv4 sockets (-Djava.net.preferIPv4Stack=true). Without it Java binds an IPv6-mapped 127.0.0.1 socket and WSL localhost forwarding only exposes it on Windows ::1, so 127.0.0.1:19092 from Windows times out.

## Ports

Both claimed in the SullaPortal registry: 19092 (facetindex/kafka, PLAINTEXT, the client port) and 19093 (facetindex/kafka-controller, KRaft CONTROLLER). Both listeners bind 127.0.0.1 only and advertise 127.0.0.1.

## Config choices

Config file: C:\SullaPortal\data\facetindex\kafka\config\server-wsl.properties (written by `kafka_service.ps1 setup`).

- num.partitions=4, default.replication.factor=1, auto.create.topics.enable=false: topics are created explicitly.
- log.retention.hours=-1, log.retention.bytes=-1, log.cleaner.enable=false, log.retention.check.interval.ms=1h: retention and compaction never run, so nothing is deleted or rewritten during a benchmark. The data dir grows until the topic is reset.
- log.segment.bytes=1073741824, log.roll.hours=720: few segment rolls.
- group.initial.rebalance.delay.ms=0: consumers start fetching immediately (no 3 s wait per run).
- Heap -Xmx1g -Xms1g, num.io.threads=4, num.network.threads=3: modest for a 2 vCPU VM.
- Storage formatted with a random cluster id and --standalone (dynamic KRaft quorum, required with controller.quorum.bootstrap.servers).
- The stock bin\windows\kafka-run-class.bat overflows cmd's line limit at this path depth ("The input line is too long"), so setup patches it to use a libs\* classpath wildcard (original kept as kafka-run-class.bat.orig). The Windows tools print a harmless "Log4j 1.x configuration file has been detected" notice.

## Service

SullaPortal service facetindex/kafka (Task Scheduler \SullaPortal\facetindex\svc\kafka, supervised, restarts on crash, starts at boot). It runs `wsl.exe -d Ubuntu-24.04 --exec bash /mnt/c/Mac/Documents/FacetIndex/scripts/kafka_wsl_run.sh`, which stops any leftover broker, formats storage if needed and execs kafka-server-start.sh. Service log: C:\SullaPortal\logs\facetindex\svc\kafka\service.log. Kafka's own logs: /home/sulla_claw/facetindex-kafka/logs in WSL.

Idle cost: about 280 MB RSS inside the WSL VM and about 3 to 8 percent of one core (polling threads, mostly kafka-raft-io), measured while the box was otherwise busy.

## Commands

All from pwsh on the mini PC:

    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 status
    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 reset-topic -Partitions 4   # delete + recreate catalog-events, empty
    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 stop                        # SIGTERM in WSL; the supervisor may cut the shutdown short, Kafka recovers on next start
    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 start
    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 setup                       # idempotent; re-creates anything missing

## Removing at the end

    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 remove          # stop, unregister service, release 19092 and 19093
    & C:\Mac\Documents\FacetIndex\scripts\kafka_service.ps1 remove -Purge   # also delete C:\SullaPortal\data\facetindex\kafka and ~/facetindex-kafka in WSL

## Smoke test (2026-10-03, box under heavy load from other work)

200-byte records, acks=1, Windows client to the WSL broker: 100k records produced at 19.1k rec/s, consumed at 15.4k rec/s (JVM warmup dominated); 1M records produced at 70.7k rec/s (13.5 MB/s), consumed at 108.5k rec/s (20.7 MB/s). Topic delete and recreate worked with no errors; catalog-events was left empty with 4 partitions.
