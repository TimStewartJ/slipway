# Creates the Prism play instance "Slipway - Minecraft 26.3 (Fabric)" (id Slipway-MC-26.3-Fabric): Minecraft 26.3,
# Fabric loader 0.19.5, Java 25 with ZGC and 24 GB like the Tellus 26.3 instance, and only Fabric API, Sodium, Iris,
# Distant Horizons (copied from the Tellus 26.3 instance, checksums verified; or the given -DhJar, such as the
# leak-fixed build) plus the given Slipway jar. Copies the Bliss shader pack (with its settings) and enables it, and
# copies options.txt with Slipway's three keys moved off the keys that layout already uses. No test agent, no test
# jars. Refuses to touch an existing instance unless -Replace.
param(
	[Parameter(Mandatory)][string]$SlipwayJar,
	[string]$DhJar,
	[string]$SourceInstance = 'Tellus-Expeditions-MC-26.3-Fabric',
	[string]$InstanceId = 'Slipway-MC-26.3-Fabric',
	[string]$DisplayName = 'Slipway - Minecraft 26.3 (Fabric)',
	[switch]$Replace
)
$ErrorActionPreference = 'Stop'
$prism = Join-Path $env:APPDATA 'PrismLauncher\instances'
$source = Join-Path $prism $SourceInstance
$dest = Join-Path $prism $InstanceId
if (-not (Test-Path (Join-Path $source 'instance.cfg'))) { throw "Source instance not found: $source" }
if (Test-Path $dest) {
	if (-not $Replace) { throw "$dest exists; pass -Replace to rebuild it" }
	if (Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape($InstanceId) }) {
		throw "The instance is running; close it first"
	}
	Remove-Item $dest -Recurse -Force
}
$mc = Join-Path $dest '.minecraft'
New-Item -ItemType Directory -Force (Join-Path $mc 'mods'), (Join-Path $mc 'shaderpacks'), (Join-Path $mc 'config'), (Join-Path $mc 'saves') | Out-Null

# Instance settings: the Tellus instance's Java, memory and JVM arguments.
$src = @{}
foreach ($line in Get-Content (Join-Path $source 'instance.cfg')) { if ($line -match '^([^=]+)=(.*)$') { $src[$matches[1]] = $matches[2] } }
$javaPath = "$env:USERPROFILE\.jdks\jdk-25.0.3+9\bin\javaw.exe".Replace('\', '/')
$dhName = if ($DhJar) { Split-Path $DhJar -Leaf } else { (@(Get-ChildItem (Join-Path $source '.minecraft\mods') -Filter 'DistantHorizons-fabric-*.jar') | Select-Object -First 1).Name }
@(
	'[General]', 'ConfigVersion=1.2', 'InstanceType=OneSix', 'iconKey=default', "name=$DisplayName",
	"notes=Slipway play instance ($(Split-Path $SlipwayJar -Leaf)) with Fabric API 0.160.7, Sodium 0.9.2, Iris 1.11.6 (Bliss v2.1.2) and $dhName. Settings copied from $SourceInstance. See E:\Slipway\PLAYTEST.md.",
	'IgnoreJavaCompatibility=true', 'JavaArchitecture=64', 'JavaRealArchitecture=amd64', 'JavaVendor=Eclipse Adoptium', 'JavaVersion=25.0.3',
	"JavaPath=$javaPath", 'OverrideJavaLocation=true', 'OverrideJavaArgs=true', "JvmArgs=$($src['JvmArgs'])",
	'OverrideMemory=true', "MaxMemAlloc=$($src['MaxMemAlloc'])", "MinMemAlloc=$($src['MinMemAlloc'])",
	'ManagedPack=false', 'LogPrePostOutput=true', 'ShowConsoleOnError=true', 'RecordGameTime=true', 'ShowGameTime=true'
) | Set-Content (Join-Path $dest 'instance.cfg') -Encoding UTF8
Copy-Item (Join-Path $source 'mmc-pack.json') (Join-Path $dest 'mmc-pack.json')

# Mods: the four from the play stack (never Tellus or Expeditions; Distant Horizons from -DhJar when given), then Slipway.
$installed = [System.Collections.Generic.List[object]]::new()
foreach ($pattern in 'fabric-api-*.jar', 'sodium-fabric-*.jar', 'iris-fabric-*.jar', 'DistantHorizons-fabric-*.jar') {
	$jar = if ($DhJar -and $pattern -like 'DistantHorizons*') { @(Get-Item $DhJar) } else { @(Get-ChildItem (Join-Path $source '.minecraft\mods') -Filter $pattern) }
	if ($jar.Count -ne 1) { throw "Expected one $pattern in $source, found $($jar.Count)" }
	$target = Join-Path $mc "mods\$($jar[0].Name)"
	Copy-Item $jar[0].FullName $target
	$a = (Get-FileHash $jar[0].FullName -Algorithm SHA256).Hash; $b = (Get-FileHash $target -Algorithm SHA256).Hash
	if ($a -ne $b) { throw "Checksum mismatch copying $($jar[0].Name)" }
	$installed.Add([pscustomobject]@{ Jar = $jar[0].Name; Sha256 = $b })
}
$slipwayTarget = Join-Path $mc "mods\$(Split-Path $SlipwayJar -Leaf)"
Copy-Item $SlipwayJar $slipwayTarget
$a = (Get-FileHash $SlipwayJar -Algorithm SHA256).Hash; $b = (Get-FileHash $slipwayTarget -Algorithm SHA256).Hash
if ($a -ne $b) { throw 'Checksum mismatch copying the Slipway jar' }
$installed.Add([pscustomobject]@{ Jar = (Split-Path $SlipwayJar -Leaf); Sha256 = $b })

# Bliss, with the player's settings for it, enabled in Iris.
$bliss = @(Get-ChildItem (Join-Path $source '.minecraft\shaderpacks') -Filter 'Bliss_v2.1.2*.zip')
if ($bliss.Count -ne 1) { throw 'Bliss v2.1.2 shader pack not found in the source instance' }
Copy-Item $bliss[0].FullName (Join-Path $mc 'shaderpacks')
$blissSettings = "$($bliss[0].FullName).txt"
if (Test-Path $blissSettings) { Copy-Item $blissSettings (Join-Path $mc 'shaderpacks') }
$iris = Join-Path $source '.minecraft\config\iris.properties'
$props = if (Test-Path $iris) { @(Get-Content $iris | Where-Object { $_ -notmatch '^(shaderPack|enableShaders)=' }) } else { @() }
$props + @("enableShaders=true", "shaderPack=$($bliss[0].Name)") | Set-Content (Join-Path $mc 'config\iris.properties') -Encoding UTF8

# options.txt with Slipway's keys moved off drop (Z), hotbar 9 (B) and quick actions (H) in that layout.
$options = @(Get-Content (Join-Path $source '.minecraft\options.txt') | Where-Object { $_ -notmatch '^key_key\.slipway\.' })
$options += @('key_key.slipway.descend:key.keyboard.left.alt', 'key_key.slipway.toggle_hover:key.keyboard.y', 'key_key.slipway.toggle_level:key.keyboard.j')
$options | Set-Content (Join-Path $mc 'options.txt') -Encoding UTF8

$installed
