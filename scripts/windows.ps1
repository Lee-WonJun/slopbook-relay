#Requires -Version 7.0
param(
    [ValidateSet('setup', 'verify', 'test', 'build', 'server', 'dev', 'npm', 'clojure', 'env', 'help')]
    [string] $Command = 'help'
)

$ErrorActionPreference = 'Stop'
$CommandArgs = @($args)
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolsRoot = Join-Path $projectRoot '.tools'
$toolchain = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'windows/toolchain.json') | ConvertFrom-Json

if ($Command -eq 'server') {
    . (Join-Path $PSScriptRoot 'windows/environment.ps1')
    Import-SlopbookLocalEnvironment -Path (Join-Path $projectRoot '.env.local')
}

if ($Command -eq 'help') {
    Write-Output @'
Slopbook on Windows (PowerShell 7, JDK 21)
  .\scripts\windows.ps1 setup             Install the pinned local tools and npm dependencies
  .\scripts\windows.ps1 verify            Run server/client tests, release build, architecture and tickets
  .\scripts\windows.ps1 build             Build the standalone jar
  .\scripts\windows.ps1 server            Start the backend at http://localhost:8080
  .\scripts\windows.ps1 dev               Watch ClojureScript (use a second terminal for server)
  .\scripts\windows.ps1 npm <args...>      Run npm with the project toolchain
  .\scripts\windows.ps1 clojure <args...>  Run the pinned Clojure CLI
  .\scripts\windows.ps1 env               Activate this terminal's environment for npm commands
'@
    return
}

function Install-ProjectTool([string] $Name, $Spec) {
    $toolDirectory = Join-Path $toolsRoot $Spec.directory
    if (Test-Path -LiteralPath (Join-Path $toolDirectory $Spec.executable)) {
        return $toolDirectory
    }
    if ($Command -ne 'setup') {
        throw "Missing $Name $($Spec.version). Run .\scripts\windows.ps1 setup."
    }
    $downloadDirectory = Join-Path $toolsRoot 'downloads'
    New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
    $archive = Join-Path $downloadDirectory "$Name-$($Spec.version).zip"
    Write-Host "Installing $Name $($Spec.version) into .tools"
    Invoke-WebRequest -Uri $Spec.url -OutFile $archive -TimeoutSec 180
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Spec.sha256) {
        throw "SHA256 verification failed for $Name. The archive was not extracted."
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
    if (-not (Test-Path -LiteralPath (Join-Path $toolDirectory $Spec.executable))) {
        throw "The $Name archive did not contain the expected executable."
    }
    return $toolDirectory
}

function Find-Java21 {
    $settingsPath = Join-Path $toolsRoot 'windows.local.json'
    $candidates = @()
    if (Test-Path -LiteralPath $settingsPath) {
        $settings = Get-Content -Raw -LiteralPath $settingsPath | ConvertFrom-Json
        if ($settings.javaHome) { $candidates += Join-Path $settings.javaHome 'bin/java.exe' }
    }
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin/java.exe' }
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) { $candidates += $javaCommand.Source }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $versionOutput = (& $candidate -version 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -and $versionOutput -match 'version "21\.') {
            return Split-Path (Split-Path $candidate -Parent) -Parent
        }
    }
    throw 'JDK 21 is required. Set JAVA_HOME or .tools/windows.local.json javaHome to an installed JDK 21.'
}

$env:JAVA_HOME = Find-Java21
$nodeDirectory = Install-ProjectTool 'node' $toolchain.node
$clojureDirectory = Install-ProjectTool 'clojure' $toolchain.clojure
$webpDirectory = Install-ProjectTool 'webp' $toolchain.webp
$env:Path = (@(
    (Join-Path $PSScriptRoot 'windows'),
    $nodeDirectory,
    (Join-Path $env:JAVA_HOME 'bin'),
    (Join-Path $webpDirectory 'bin'),
    $env:Path
) -join [IO.Path]::PathSeparator)
# Clojure source and contract fixtures are UTF-8 on both development platforms.
if ($env:JAVA_TOOL_OPTIONS -notmatch '(?:^|\s)-Dfile\.encoding=UTF-8(?:\s|$)') {
    $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, '-Dfile.encoding=UTF-8' | Where-Object { $_ }) -join ' ')
}

if ($Command -eq 'env') {
    Write-Host 'Slopbook toolchain is active in this terminal. Use npm commands here.'
    return
}

$nodeExecutable = Join-Path $nodeDirectory 'node.exe'
$npmCli = Join-Path $nodeDirectory 'node_modules/npm/bin/npm-cli.js'
$clojureShim = Join-Path $PSScriptRoot 'windows/clojure.cmd'
Push-Location $projectRoot
try {
    switch ($Command) {
        'setup' {
            & $nodeExecutable --version
            & $clojureShim -Sdescribe
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            & (Join-Path $webpDirectory 'bin/cwebp.exe') -version
            & $nodeExecutable $npmCli ci
        }
        'npm' { & $nodeExecutable $npmCli @CommandArgs }
        'clojure' { & $nodeExecutable (Join-Path $PSScriptRoot 'windows/clojure.mjs') @CommandArgs }
        'verify' { & $nodeExecutable $npmCli run verify @CommandArgs }
        'test' { & $nodeExecutable $npmCli test @CommandArgs }
        'build' { & $nodeExecutable $npmCli run uberjar @CommandArgs }
        'server' { & $nodeExecutable $npmCli run server @CommandArgs }
        'dev' { & $nodeExecutable $npmCli run dev @CommandArgs }
    }
    $commandExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $commandExitCode
