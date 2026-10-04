# PowerShell entry point on the mini PC: runs one phase of scripts/run_experiments.sh with Git Bash.
#   pwsh scripts/minipc_run.ps1 exp1-q10k-private
param([Parameter(Mandatory)][string]$Phase)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\SullaPortal\data\facetindex\jdk\jdk-21.0.12.1+1' }
$env:GRADLE_USER_HOME = 'C:\SullaPortal\data\facetindex\gradle'
$env:FACETINDEX_DATA = if ($env:FACETINDEX_DATA) { $env:FACETINDEX_DATA } else { 'C:/SullaPortal/data/facetindex' }
Set-Location (Split-Path $PSScriptRoot -Parent)
& 'C:\Program Files\Git\bin\bash.exe' scripts/run_experiments.sh $Phase
exit $LASTEXITCODE
