# FacetIndex single-node Kafka 4.3.1 (KRaft combined mode) running in WSL Ubuntu-24.04 as the
# SullaPortal service facetindex/kafka. Clients connect from Windows to 127.0.0.1:<claimed port>.
# Usage: kafka_service.ps1 setup|start|stop|status|reset-topic|remove [-Partitions 4] [-Topic catalog-events] [-Purge]
#   setup        download + verify + extract Kafka and the Linux JDK, patch the Windows tool classpath,
#                claim ports, write config, format storage (each step only if missing)
#   start        setup, register the service if missing (or if its command changed), start it, wait for the port
#   stop         stop the service and shut the broker down cleanly inside WSL (stays stopped until start)
#   status       service status, broker RAM/CPU inside WSL, topic list
#   reset-topic  delete and recreate -Topic with -Partitions partitions (empty)
#   remove       stop + unregister the service, release both ports (-Purge also deletes dist, JDK and data)
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('setup', 'start', 'stop', 'status', 'reset-topic', 'remove')][string]$Action,
    [int]$Partitions = 4,
    [string]$Topic = 'catalog-events',
    [switch]$Purge
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Ver     = '4.3.1'
$Tgz     = "kafka_2.13-$Ver.tgz"
$Mirrors = @("https://downloads.apache.org/kafka/$Ver", "https://archive.apache.org/dist/kafka/$Ver")
$Root    = 'C:\SullaPortal\data\facetindex\kafka'
$Dist    = "$Root\kafka_2.13-$Ver"
$Bin     = "$Dist\bin\windows"
$CfgWsl  = "$Root\config\server-wsl.properties"
$WinJdk  = 'C:\SullaPortal\data\facetindex\jdk\jdk-21.0.12.1+1'    # for the Windows client tools only
$LinJdk  = "$Root\linux-jdk"
$LinJdkV = 'jdk-21.0.12.1+1'
$Distro  = 'Ubuntu-24.04'
$RunSh   = '/mnt/c/Mac/Documents/FacetIndex/scripts/kafka_wsl_run.sh'
$SpBin   = 'C:\SullaPortal\bin'
$Proj    = 'facetindex'
$Svc     = 'kafka'

function Wsl([string]$cmd) { & wsl.exe -d $Distro -- bash -c $cmd }

function Get-Ports {
    $b = [int](& "$SpBin\ports.ps1" -Action claim -Project $Proj -Name kafka -Preferred 19092 | Select-Object -Last 1)
    $c = [int](& "$SpBin\ports.ps1" -Action claim -Project $Proj -Name kafka-controller -Preferred 19093 | Select-Object -Last 1)
    @{ Broker = $b; Controller = $c }
}

function Set-ToolEnv {
    $env:JAVA_HOME = $WinJdk
    $env:LOG_DIR = "$Root\tool-logs"
    $env:KAFKA_HEAP_OPTS = '-Xmx512m'
    $env:KAFKA_LOG4J_OPTS = '-Dlog4j2.configurationFile=file:/' + ("$Dist\config\tools-log4j2.yaml" -replace '\\', '/')
}

function Get-File([string]$name) {
    $dst = "$Root\$name"
    if (Test-Path $dst) { return $dst }
    foreach ($m in $Mirrors) { & curl.exe -sSfL -o $dst "$m/$name"; if ($LASTEXITCODE -eq 0) { return $dst } }
    Remove-Item $dst -Force -ErrorAction SilentlyContinue; throw "download of $name failed"
}

function Invoke-Setup {
    New-Item -ItemType Directory -Force $Root, "$Root\config", $LinJdk | Out-Null
    # 1. Kafka dist, SHA-512 verified.
    if (-not (Test-Path "$Dist\bin\kafka-server-start.sh")) {
        $tgzPath = Get-File $Tgz
        $want = ((Get-Content (Get-File "$Tgz.sha512") -Raw) -replace '^[^:]*:', '' -replace '\s', '').ToUpper()
        if ((Get-FileHash $tgzPath -Algorithm SHA512).Hash.ToUpper() -ne $want) { Remove-Item $tgzPath -Force; throw "SHA-512 mismatch for $Tgz (deleted, rerun)" }
        Write-Host "SHA-512 OK for $Tgz"
        & tar.exe -xzf $tgzPath -C $Root; if ($LASTEXITCODE -ne 0) { throw 'extract failed' }
    }
    # 2. Windows tools: stock kafka-run-class.bat lists every jar by full path and overflows cmd's
    #    8191-char line limit at this install depth. Use a classpath wildcard instead.
    $rc = "$Bin\kafka-run-class.bat"; $t = [IO.File]::ReadAllText($rc)
    $nl = if ($t.Contains("`r`n")) { "`r`n" } else { "`n" }
    $old = "for %%i in (`"%BASE_DIR%\libs\*`") do ($nl`tcall :concat `"%%i`"$nl)"
    if ($t.Contains($old)) { Copy-Item $rc "$rc.orig" -Force; [IO.File]::WriteAllText($rc, $t.Replace($old, "call :concat `"%BASE_DIR%\libs\*`"")); Write-Host 'patched kafka-run-class.bat' }
    # 3. Linux JDK (Temurin 21) for the broker in WSL, SHA-256 verified against the Adoptium API.
    if (-not (Test-Path "$LinJdk\$LinJdkV\bin\java")) {
        $pk = (& curl.exe -sSfL 'https://api.adoptium.net/v3/assets/latest/21/hotspot?os=linux&architecture=x64&image_type=jdk&vendor=eclipse' | ConvertFrom-Json)[0].binary.package
        $f = "$LinJdk\$($pk.name)"
        if (-not (Test-Path $f)) { & curl.exe -sSfL -o $f $pk.link; if ($LASTEXITCODE -ne 0) { throw 'JDK download failed' } }
        if ((Get-FileHash $f -Algorithm SHA256).Hash.ToLower() -ne $pk.checksum.ToLower()) { Remove-Item $f -Force; throw 'JDK SHA-256 mismatch' }
        Wsl "cd /mnt/c/SullaPortal/data/facetindex/kafka/linux-jdk && tar -xzf '$($pk.name)'"
        if (-not (Test-Path "$LinJdk\$LinJdkV\bin\java")) { throw "expected $LinJdkV after extracting $($pk.name); update `$LinJdkV here and in kafka_wsl_run.sh" }
    }
    # 4. Config. Data lives on the WSL ext4 disk (see KAFKA.md).
    $p = Get-Ports
    $home_ = (Wsl 'echo $HOME' | Select-Object -Last 1).Trim()
    $conf = @"
# FacetIndex single-node KRaft broker in WSL. Written by scripts\kafka_service.ps1 setup.
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=127.0.0.1:$($p.Controller)
listeners=PLAINTEXT://127.0.0.1:$($p.Broker),CONTROLLER://127.0.0.1:$($p.Controller)
advertised.listeners=PLAINTEXT://127.0.0.1:$($p.Broker),CONTROLLER://127.0.0.1:$($p.Controller)
inter.broker.listener.name=PLAINTEXT
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
num.network.threads=3
num.io.threads=4
socket.request.max.bytes=104857600
log.dirs=$home_/facetindex-kafka/data
num.partitions=4
default.replication.factor=1
auto.create.topics.enable=false
num.recovery.threads.per.data.dir=2
offsets.topic.replication.factor=1
offsets.topic.num.partitions=4
share.coordinator.state.topic.replication.factor=1
share.coordinator.state.topic.min.isr=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
# Retention and cleanup never trigger during a benchmark.
log.retention.hours=-1
log.retention.bytes=-1
log.retention.check.interval.ms=3600000
log.cleaner.enable=false
log.segment.bytes=1073741824
log.roll.hours=720
"@
    $conf = $conf -replace "`r`n", "`n"
    if (-not (Test-Path $CfgWsl) -or [IO.File]::ReadAllText($CfgWsl) -ne $conf) { [IO.File]::WriteAllText($CfgWsl, $conf); Write-Host "wrote $CfgWsl" }
    # 5. Format storage (no-op if already formatted).
    Wsl "bash $RunSh --format-only" | Out-Host
    $p
}

function Get-SvcCommand {
    @"
& wsl.exe -d $Distro -- bash $RunSh
exit `$LASTEXITCODE
"@
}

function Test-Registered { [bool](Get-ScheduledTask -TaskPath "\SullaPortal\$Proj\svc\" -TaskName $Svc -ErrorAction SilentlyContinue) }

function Wait-Broker([int]$port, [int]$sec = 120) {
    Set-ToolEnv
    $deadline = (Get-Date).AddSeconds($sec)
    while ((Get-Date) -lt $deadline) {
        if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
            & "$Bin\kafka-broker-api-versions.bat" --bootstrap-server "127.0.0.1:$port" 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { return $true }
        }
        Start-Sleep -Seconds 2
    }
    $false
}

function Stop-BrokerInWsl {
    Wsl "pkill -TERM -f 'kafka.Kafka /mnt/c/SullaPortal/data/facetindex/kafka/config/server-wsl.properties' && for i in `$(seq 1 60); do pgrep -f 'kafka.Kafka /mnt/c/SullaPortal' >/dev/null || break; sleep 1; done; true"
}

function Invoke-Topics([string[]]$a) {
    Set-ToolEnv
    & "$Bin\kafka-topics.bat" --bootstrap-server "127.0.0.1:$((Get-Ports).Broker)" @a
}

switch ($Action) {
    'setup' { $p = Invoke-Setup; Write-Output "ready: broker 127.0.0.1:$($p.Broker), controller 127.0.0.1:$($p.Controller)" }

    'start' {
        $p = Invoke-Setup
        $cmd = Get-SvcCommand
        $sj = "C:\SullaPortal\services\$Proj\$Svc\service.json"
        $cur = if (Test-Path $sj) { (Get-Content $sj -Raw | ConvertFrom-Json).command } else { $null }
        if (-not (Test-Registered) -or $cur -ne $cmd) {
            $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cmd))
            & "$SpBin\service.ps1" -Action add -Project $Proj -Name $Svc -Command $b64 -Cwd $Root -Port $p.Broker
        } else {
            & "$SpBin\service.ps1" -Action start -Project $Proj -Name $Svc
        }
        if (Wait-Broker $p.Broker) { Write-Output "broker up on 127.0.0.1:$($p.Broker)" }
        else { Write-Output 'broker not answering yet; see: C:\SullaPortal\bin\service.ps1 -Action logs -Project facetindex -Name kafka' }
    }

    'stop' {
        & "$SpBin\service.ps1" -Action stop -Project $Proj -Name $Svc
        Stop-BrokerInWsl
    }

    'status' {
        & "$SpBin\service.ps1" -Action status -Project $Proj -Name $Svc
        Wsl "pid=`$(pgrep -f 'kafka.Kafka /mnt/c/SullaPortal' | head -1); if [ -n `"`$pid`" ]; then ps -o pid,rss,pcpu,etime -p `$pid | awk 'NR==1{print `"broker (WSL):`", `$0} NR==2{printf `"broker (WSL): pid %s rss %.0f MB avgcpu %s%% up %s\n`", `$1, `$2/1024, `$3, `$4}'; else echo 'broker process not running in WSL'; fi"
        Invoke-Topics @('--list')
    }

    'reset-topic' {
        if (@(Invoke-Topics @('--list')) -contains $Topic) {
            Invoke-Topics @('--delete', '--topic', $Topic)
            for ($i = 0; $i -lt 60; $i++) { if (@(Invoke-Topics @('--list')) -notcontains $Topic) { break }; Start-Sleep -Seconds 1 }
        }
        Invoke-Topics @('--create', '--topic', $Topic, '--partitions', "$Partitions", '--replication-factor', '1')
        Invoke-Topics @('--describe', '--topic', $Topic)
    }

    'remove' {
        if (Test-Registered) { & "$SpBin\service.ps1" -Action stop -Project $Proj -Name $Svc }
        Stop-BrokerInWsl
        & "$SpBin\service.ps1" -Action remove -Project $Proj -Name $Svc
        & "$SpBin\ports.ps1" -Action release -Project $Proj -Name kafka
        & "$SpBin\ports.ps1" -Action release -Project $Proj -Name kafka-controller
        if ($Purge) {
            Wsl 'rm -rf "$HOME/facetindex-kafka"'
            Remove-Item $Root -Recurse -Force
            Write-Output "deleted $Root and ~/facetindex-kafka in WSL"
        }
    }
}
