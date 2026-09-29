<#
.SYNOPSIS Copies the integration mods Slipway builds and tests against into devmods/ (git-ignored).
.DESCRIPTION Fabric API, Sodium, Iris and Distant Horizons are taken from the play instance so the build, the test
instance and the final play instance all use exactly the jars of the user's play stack. Tellus and Tellus
Expeditions are never copied.
#>
[CmdletBinding()]
param(
	[string]$SourceInstance = 'Tellus-Expeditions-MC-26.3-Fabric',
	[string]$PrismRoot = "$env:APPDATA\PrismLauncher"
)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PrismRoot "instances\$SourceInstance\.minecraft\mods"
$target = Join-Path (Split-Path $PSScriptRoot -Parent) 'devmods'
New-Item -ItemType Directory -Force $target | Out-Null
$wanted = @(
	'fabric-api-0.160.7+26.3.jar',
	'sodium-fabric-0.9.2+mc26.3.jar',
	'iris-fabric-1.11.6+mc26.3.jar',
	'DistantHorizons-fabric-3.3.1-tellus-fork.6-26.3.jar'
)
foreach ($name in $wanted) {
	$from = Join-Path $source $name
	if (-not (Test-Path -LiteralPath $from)) { throw "Missing $from" }
	Copy-Item -LiteralPath $from -Destination $target -Force
}
Get-ChildItem $target -Filter *.jar | ForEach-Object {
	[pscustomobject]@{ Jar = $_.Name; Sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash }
}
