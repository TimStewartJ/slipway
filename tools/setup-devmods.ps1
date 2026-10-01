<#
.SYNOPSIS Copies the integration mods Slipway builds and tests against from the author's Prism instance into devmods/.
.DESCRIPTION The author's workflow: Fabric API, Sodium, Iris and Distant Horizons are taken from the play instance so
the build, the tests and the final play instance all use exactly the jars of the author's play stack. Everyone
else uses tools/fetch-devmods.py, which downloads the pinned Modrinth releases. Tellus and Tellus Expeditions are
never copied. Distant Horizons is whichever build the instance has: the Tellus fork, 3.3.4-tellus-fork.7 or later.
Older fork builds keep every closed world in memory and fail the client GameTest leak check (DESIGN.md, "World
retention"). -TestDhJar puts another Distant Horizons build into devmods/test, for the client GameTests only; without
it devmods/test is emptied and the tests use the play stack's build.
#>
[CmdletBinding()]
param(
	[string]$SourceInstance = 'Tellus-Expeditions-MC-26.3-Fabric',
	[string]$PrismRoot = "$env:APPDATA\PrismLauncher",
	[string]$TestDhJar
)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PrismRoot "instances\$SourceInstance\.minecraft\mods"
$target = Join-Path (Split-Path $PSScriptRoot -Parent) 'devmods'
New-Item -ItemType Directory -Force $target | Out-Null
$dh = @(Get-ChildItem $source -Filter 'DistantHorizons-fabric-*.jar')
if ($dh.Count -ne 1) { throw "Expected one DistantHorizons-fabric-*.jar in $source, found $($dh.Count)" }
$wanted = @(
	'fabric-api-0.160.7+26.3.jar',
	'sodium-fabric-0.9.2+mc26.3.jar',
	'iris-fabric-1.11.6+mc26.3.jar',
	$dh[0].Name
)
# The build takes the last DistantHorizons-fabric-*.jar by name, so an older build must not stay behind.
Get-ChildItem $target -Filter 'DistantHorizons-*.jar' | Where-Object { $_.Name -ne $dh[0].Name } | Remove-Item -Force
foreach ($name in $wanted) {
	$from = Join-Path $source $name
	if (-not (Test-Path -LiteralPath $from)) { throw "Missing $from" }
	Copy-Item -LiteralPath $from -Destination $target -Force
}
$testDir = Join-Path $target 'test'
if (Test-Path $testDir) { Get-ChildItem $testDir -Filter 'DistantHorizons-*.jar' | Remove-Item -Force }
if ($TestDhJar) {
	if (-not (Test-Path -LiteralPath $TestDhJar)) { throw "Missing $TestDhJar" }
	New-Item -ItemType Directory -Force $testDir | Out-Null
	Copy-Item -LiteralPath $TestDhJar -Destination $testDir -Force
}
Get-ChildItem $target -Filter *.jar -Recurse | ForEach-Object {
	[pscustomobject]@{ Jar = $_.FullName.Substring($target.Length + 1); Sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash }
}
