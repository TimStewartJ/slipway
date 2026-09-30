<#
.SYNOPSIS Copies the integration mods Slipway builds and tests against from the author's Prism instance into devmods/.
.DESCRIPTION The author's workflow: Fabric API, Sodium, Iris and Distant Horizons are taken from the play instance so
the build, the test instance and the final play instance all use exactly the jars of the author's play stack. Everyone
else uses tools/fetch-devmods.py, which downloads the pinned Modrinth releases. Tellus and Tellus Expeditions are
never copied. The client GameTests run with the leak-fixed Distant Horizons build (branch slipway-leak-fix of the
author's Distant Horizons checkout, $env:SLIPWAY_DH_REPO, default E:\distant-horizons; DESIGN.md "World retention"),
copied to devmods/test when it has been built; with the play stack's fork.6 the client GameTest leak check fails,
because fork.6 keeps every closed world.
#>
[CmdletBinding()]
param(
	[string]$SourceInstance = 'Tellus-Expeditions-MC-26.3-Fabric',
	[string]$PrismRoot = "$env:APPDATA\PrismLauncher",
	[string]$TestDhJar = (Get-ChildItem (Join-Path $(if ($env:SLIPWAY_DH_REPO) { $env:SLIPWAY_DH_REPO } else { 'E:\distant-horizons' }) 'fabric\build\libs') -Filter 'DistantHorizons-fabric-*-leakfix.*-26.3.jar' -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTime | Select-Object -Last 1).FullName
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
if ($TestDhJar -and (Test-Path -LiteralPath $TestDhJar)) {
	$testDir = Join-Path $target 'test'
	New-Item -ItemType Directory -Force $testDir | Out-Null
	Get-ChildItem $testDir -Filter 'DistantHorizons-*.jar' | Remove-Item -Force
	Copy-Item -LiteralPath $TestDhJar -Destination $testDir -Force
} else {
	Write-Warning 'No leak-fixed Distant Horizons build found; the client GameTests will use fork.6 and their leak check will fail.'
}
Get-ChildItem $target -Filter *.jar -Recurse | ForEach-Object {
	[pscustomobject]@{ Jar = $_.FullName.Substring($target.Length + 1); Sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash }
}
