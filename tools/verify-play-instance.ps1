# Launches the play instance natively (no test agent) straight into the sandbox world, checks that Slipway loads with
# no mod or mixin errors, that the world opens and the demo vessel is there with Bliss on, takes a window screenshot,
# closes the game cleanly and verifies every installed mod jar against the expected checksums. The instance is never
# given focus. Minecraft's own quick-play argument opens the world: Prism 8.3 cannot pass it, so a temporary Prism
# component adds it for this one launch and is removed afterwards.
param(
	[string]$InstanceId = 'Slipway-MC-26.3-Fabric',
	[string]$WorldFolder = 'SlipwaySandbox',
	[Parameter(Mandatory)][hashtable]$ExpectedSha256,
	[Parameter(Mandatory)][string]$OutDir
)
$ErrorActionPreference = 'Stop'
. "$HOME\.copilot\skills\minecraft-window-automation\scripts\MinecraftWindowAutomation.ps1"
Import-Module (Join-Path $PSScriptRoot 'e2e\SlipwayE2E.psm1') -Force
New-Item -ItemType Directory -Force $OutDir | Out-Null
$dir = Join-Path $env:APPDATA "PrismLauncher\instances\$InstanceId"
$mc = Join-Path $dir '.minecraft'
$pack = Join-Path $dir 'mmc-pack.json'
$patchDir = Join-Path $dir 'patches'
$patch = Join-Path $patchDir 'slipway.quickplay.json'
$packBackup = Get-Content $pack -Raw
$result = [ordered]@{}

# 1. Mods on disk are exactly the expected ones.
$jars = @(Get-ChildItem (Join-Path $mc 'mods') -Filter '*.jar')
$result.Mods = foreach ($j in $jars) {
	$h = (Get-FileHash $j.FullName -Algorithm SHA256).Hash
	[pscustomobject]@{ Jar = $j.Name; Sha256 = $h; Expected = $ExpectedSha256[$j.Name]; Ok = ($ExpectedSha256[$j.Name] -eq $h) }
}
$result.ModsOk = ($jars.Count -eq $ExpectedSha256.Count) -and -not ($result.Mods | Where-Object { -not $_.Ok })

# 2. Temporary quick-play component.
$vanilla = (Get-Content (Join-Path $env:APPDATA 'PrismLauncher\meta\net.minecraft\26.3.json') -Raw | ConvertFrom-Json).minecraftArguments
New-Item -ItemType Directory -Force $patchDir | Out-Null
[ordered]@{ formatVersion = 1; name = 'Quick play (temporary)'; uid = 'slipway.quickplay'; version = '1'; order = 50
	minecraftArguments = "$vanilla --quickPlaySingleplayer $WorldFolder" } | ConvertTo-Json | Set-Content $patch -Encoding UTF8
$packJson = $packBackup | ConvertFrom-Json
$packJson.components += [pscustomobject]@{ cachedName = 'Quick play (temporary)'; cachedVersion = '1'; uid = 'slipway.quickplay'; version = '1' }
$packJson | ConvertTo-Json -Depth 8 | Set-Content $pack -Encoding UTF8

$game = $null
try {
	$logPath = Join-Path $mc 'logs\latest.log'
	$started = Get-Date
	$run = Start-CopilotPrismMinecraftInstance -InstanceId $InstanceId -TimeoutSeconds 300
	$game = $run.ProcessId
	$client = [pscustomobject]@{ ProcessId = $game }
	# Keep it behind the user's windows (never activate it).
	$deadline = (Get-Date).AddMinutes(6)
	$joined = $false
	while (-not $joined -and (Get-Date) -lt $deadline) {
		try { Set-SlipwayE2EClientBackground -Client $client | Out-Null } catch { }
		Confirm-SlipwayE2EPrismOfflinePrompt | Out-Null
		if ((Test-Path $logPath) -and (Get-Item $logPath).LastWriteTime -gt $started) {
			$joined = [bool](Select-String -LiteralPath $logPath -Pattern 'joined the game' -Quiet)
		}
		if (-not (Get-Process -Id $game -ErrorAction SilentlyContinue)) { throw 'The game exited before the world opened' }
		Start-Sleep -Seconds 2
	}
	$result.WorldOpened = $joined
	# Let chunks, the vessel's mesh, Iris and Distant Horizons settle, then look.
	Start-Sleep -Seconds 40
	try { Set-SlipwayE2EClientBackground -Client $client | Out-Null } catch { }
	$shot = Join-Path $OutDir 'play-instance-sandbox.png'
	Save-CopilotClientScreenshot -ProcessId $game -Path $shot -Force | Out-Null
	$result.Screenshot = $shot
	$log = Get-Content $logPath
	$result.Slipway = @($log | Select-String -Pattern 'Loading \d+ mods|slipway 0\.1\.0|jolt-jni .* loaded|Assembled|\[Slipway' | ForEach-Object { $_.Line } | Select-Object -First 8)
	$result.Iris = @($log | Select-String -Pattern 'Using shaderpack|shaderPackInUse|Bliss' | ForEach-Object { $_.Line } | Select-Object -First 3)
	$result.Problems = @(Get-SlipwayE2ELogProblems -LogPath $logPath -Offset 0)
	$result.MixinErrors = @($log | Select-String -Pattern 'Mixin apply|InvalidInjection|InjectionError|Critical injection failure|mixin.*[Ff]ailed' | ForEach-Object { $_.Line })
} finally {
	if ($game -and (Get-Process -Id $game -ErrorAction SilentlyContinue)) {
		$result.ClosedCleanly = [bool](Close-CopilotWindowGracefully -ProcessId $game -TimeoutSeconds 120)
		$wait = (Get-Date).AddSeconds(120)
		while ((Get-Process -Id $game -ErrorAction SilentlyContinue) -and (Get-Date) -lt $wait) { Start-Sleep -Seconds 1 }
		$result.Exited = -not (Get-Process -Id $game -ErrorAction SilentlyContinue)
	}
	# Restore the instance exactly: no quick-play component left behind.
	Set-Content $pack $packBackup -NoNewline -Encoding UTF8
	Remove-Item $patch -Force -ErrorAction SilentlyContinue
	if (-not (Get-ChildItem $patchDir -ErrorAction SilentlyContinue)) { Remove-Item $patchDir -Force -ErrorAction SilentlyContinue }
	$result.InstanceRestored = (Get-Content $pack -Raw) -eq $packBackup -and -not (Test-Path $patch)
}
if (Test-Path (Join-Path $mc 'logs\latest.log')) {
	$saved = @(Select-String -LiteralPath (Join-Path $mc 'logs\latest.log') -Pattern 'Saved \d+ vessels|Stopping server|Saving chunks' | ForEach-Object { $_.Line })
	$result.ShutdownLog = $saved | Select-Object -Last 4
	Copy-Item (Join-Path $mc 'logs\latest.log') (Join-Path $OutDir 'play-instance-latest.log') -Force
}
[pscustomobject]$result
