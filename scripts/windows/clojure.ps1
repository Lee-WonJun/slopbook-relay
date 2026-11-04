#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$modulePath = Join-Path $projectRoot '.tools/ClojureTools/ClojureTools.psd1'
if (-not (Test-Path -LiteralPath $modulePath)) {
    throw 'Run .\scripts\windows.ps1 setup before invoking Clojure.'
}
Import-Module -Name $modulePath -Force
$clojureArguments = @(ConvertFrom-Json -InputObject $env:SLOPBOOK_CLOJURE_ARGS_JSON)
Remove-Item Env:SLOPBOOK_CLOJURE_ARGS_JSON
$global:LASTEXITCODE = 0
ClojureTools\Invoke-Clojure @clojureArguments
exit $LASTEXITCODE
