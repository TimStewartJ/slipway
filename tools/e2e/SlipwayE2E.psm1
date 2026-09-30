# SlipwayE2E.psm1 - end-to-end harness for Slipway on Minecraft 26.3 (Fabric).
#
# Same approach as the Tellus harness, but fully separate: a dedicated Fabric server in E:\slipway-e2e\server that
# we control over RCON, and Prism test instances (Slipway-Test-MC-26.3-Fabric and, for multiplayer,
# Slipway-Test2-MC-26.3-Fabric) that carry the test-only agent mod (tools/e2e/agent). The agent reads commands from
# <gameDir>\slipway-agent\commands.txt, so nothing here takes window focus, keyboard or mouse. Screenshots are the
# game's own framebuffer. Scenario runs are written to E:\slipway-e2e\runs\<scenario>-<stamp>\report.md and
# recorded in the repository's validation.json.
#
#   Import-Module E:\Slipway\tools\e2e\SlipwayE2E.psm1 -Force
#   $s = Start-SlipwayE2EServer -World probe
#   $c = Start-SlipwayE2EClient -Server $s
#   Invoke-SlipwayE2EAgent -Client $c -Verb state
#   Stop-SlipwayE2EAll -Server $s -Client $c

Set-StrictMode -Version 3

$script:Root = if ($env:SLIPWAY_E2E_ROOT) { $env:SLIPWAY_E2E_ROOT } else { 'E:\slipway-e2e' }
$script:Repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$script:JavaExe = "$env:USERPROFILE\.jdks\jdk-25.0.3+9\bin\java.exe"
$script:JavaW = "$env:USERPROFILE\.jdks\jdk-25.0.3+9\bin\javaw.exe"
$script:PrismRoot = "$env:APPDATA\PrismLauncher"
$script:PrismExe = "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe"
$script:InstanceA = 'Slipway-Test-MC-26.3-Fabric'
$script:InstanceB = 'Slipway-Test2-MC-26.3-Fabric'
$script:ServerPort = 25601
$script:RconPort = 25600
$script:RconPassword = 'slipway-e2e'
$script:DesktopHelper = "$env:USERPROFILE\.copilot\skills\minecraft-window-automation\scripts\MinecraftWindowAutomation.ps1"
if (Test-Path $script:DesktopHelper) { . $script:DesktopHelper }

if (-not ('SlipwayE2ENative' -as [type])) {
	Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class SlipwayE2ENative
{
    [DllImport("user32.dll", SetLastError = true)] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
}
'@
}

function Get-SlipwayE2ERoot { $script:Root }
function Get-SlipwayE2EServerDir { Join-Path $script:Root 'server' }

# ---------------------------------------------------------------------------------------------------------------
# Server
# ---------------------------------------------------------------------------------------------------------------

function Install-SlipwayE2EServer {
	[CmdletBinding()]
	param([string]$MinecraftVersion = '26.3', [string]$LoaderVersion = '0.19.5', [string]$InstallerVersion = '1.1.2')
	$dir = Get-SlipwayE2EServerDir
	New-Item -ItemType Directory -Force (Join-Path $dir 'mods') | Out-Null
	$launcher = Join-Path $dir 'fabric-server-launch.jar'
	if (-not (Test-Path $launcher)) {
		Invoke-WebRequest -Uri "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion/$LoaderVersion/$InstallerVersion/server/jar" -OutFile $launcher -TimeoutSec 120
	}
	Set-Content -Path (Join-Path $dir 'eula.txt') -Value 'eula=true' -Encoding ASCII
	$launcher
}

function Get-SlipwayE2EServerProcess {
	Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'fabric-server-launch\.jar' -and $_.CommandLine -match 'slipway\.e2e=true' } | Select-Object -First 1
}

function Set-SlipwayE2EServerMods {
	<# .SYNOPSIS Puts Fabric API and the given Slipway jar (and nothing else) into the server's mods folder. #>
	[CmdletBinding()]
	param([Parameter(Mandatory)][string]$SlipwayJar)
	if (Get-SlipwayE2EServerProcess) { throw 'The e2e server is running; stop it before replacing mods.' }
	$mods = Join-Path (Get-SlipwayE2EServerDir) 'mods'
	New-Item -ItemType Directory -Force $mods | Out-Null
	Get-ChildItem $mods -Filter *.jar | Remove-Item -Force
	Copy-Item (Join-Path $script:Repo 'devmods\fabric-api-0.160.7+26.3.jar') $mods
	Copy-Item $SlipwayJar $mods
	Get-ChildItem $mods -Filter *.jar | ForEach-Object { [pscustomobject]@{ Jar = $_.Name; Sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash } }
}

function New-SlipwayE2EWorld {
	<# .SYNOPSIS Deletes and forgets a server world so the next start generates it fresh. #>
	param([Parameter(Mandatory)][string]$Name)
	if (Get-SlipwayE2EServerProcess) { throw 'The e2e server is running; stop it first.' }
	if ($Name -notmatch '^[A-Za-z0-9_-]+$') { throw "World name '$Name' must be a simple folder name" }
	$path = Join-Path (Get-SlipwayE2EServerDir) $Name
	if (Test-Path $path) { Remove-Item $path -Recurse -Force }
	$path
}

function Start-SlipwayE2EServer {
	<# .SYNOPSIS Starts the dedicated server on a world (generated on first start) and waits for Done and RCON. #>
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)][string]$World,
		[long]$Seed = 20260929,
		[int]$ViewDistance = 10,
		[int]$SimulationDistance = 8,
		[int]$MaxHeapGb = 6,
		[string]$GameMode = 'creative',
		[bool]$AllowFlight = $false,
		[int]$TimeoutSeconds = 300
	)
	$dir = Get-SlipwayE2EServerDir
	if (Get-SlipwayE2EServerProcess) { throw 'An e2e server is already running; stop it first.' }
	$props = @(
		"level-name=$World", "level-seed=$Seed", 'online-mode=false', 'enforce-secure-profile=false', "server-port=$script:ServerPort",
		'enable-rcon=true', "rcon.port=$script:RconPort", "rcon.password=$script:RconPassword", 'broadcast-rcon-to-ops=false',
		'spawn-protection=0', "view-distance=$ViewDistance", "simulation-distance=$SimulationDistance", 'max-tick-time=-1',
		'sync-chunk-writes=false', "allow-flight=$($AllowFlight.ToString().ToLower())", 'motd=Slipway e2e', "gamemode=$GameMode",
		'difficulty=peaceful', 'spawn-monsters=false', 'generate-structures=false', 'max-players=4', 'white-list=false',
		'pause-when-empty-seconds=-1', 'level-type=minecraft\:normal'
	)
	Set-Content -Path (Join-Path $dir 'server.properties') -Value $props -Encoding ASCII
	$logs = Join-Path $dir 'logs'
	New-Item -ItemType Directory -Force $logs | Out-Null
	$stdout = Join-Path $logs "e2e-stdout-$(Get-Date -Format yyyyMMdd-HHmmssfff).log"
	$args = @("-Xmx${MaxHeapGb}G", '-XX:+UseZGC', '--enable-native-access=ALL-UNNAMED', '-Dslipway.e2e=true', '-jar', 'fabric-server-launch.jar', 'nogui')
	$proc = Start-Process -FilePath $script:JavaExe -ArgumentList $args -WorkingDirectory $dir -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError "$stdout.err"
	$server = [pscustomobject]@{ ProcessId = $proc.Id; World = $World; Directory = $dir; LogPath = (Join-Path $logs 'latest.log'); Stdout = $stdout; Port = $script:ServerPort; StartedAt = Get-Date }
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	while ($true) {
		$text = if (Test-Path $stdout) { Get-Content $stdout -Raw -ErrorAction SilentlyContinue } else { '' }
		if ($text -match 'Done \([\d.]+s\)!') { break }
		if ($proc.HasExited) { throw "The e2e server exited while starting; see $stdout" }
		if ((Get-Date) -gt $deadline) { Stop-Process -Id $proc.Id -Force; throw "The e2e server did not start within ${TimeoutSeconds}s" }
		Start-Sleep -Milliseconds 500
	}
	$rconDeadline = (Get-Date).AddSeconds(60)
	while ((Get-Content $stdout -Raw) -notmatch 'RCON running' -and (Get-Date) -lt $rconDeadline) { Start-Sleep -Milliseconds 300 }
	$server
}

function Send-SlipwayE2ERcon {
	<# .SYNOPSIS Sends one command over RCON and returns the response text. #>
	[CmdletBinding()]
	param([Parameter(Mandatory)][string]$Command, [int]$TimeoutMilliseconds = 60000)
	$client = New-Object System.Net.Sockets.TcpClient
	$client.ReceiveTimeout = $TimeoutMilliseconds; $client.SendTimeout = $TimeoutMilliseconds
	$client.Connect('127.0.0.1', $script:RconPort)
	$stream = $client.GetStream()
	function Write-Packet([int]$id, [int]$type, [string]$body) {
		$bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
		$ms = New-Object System.IO.MemoryStream; $w = New-Object System.IO.BinaryWriter($ms)
		$w.Write([int](4 + 4 + $bytes.Length + 2)); $w.Write([int]$id); $w.Write([int]$type); $w.Write($bytes); $w.Write([byte]0); $w.Write([byte]0)
		$data = $ms.ToArray(); $stream.Write($data, 0, $data.Length)
	}
	function Read-Packet {
		$hdr = New-Object byte[] 4; $got = 0
		while ($got -lt 4) { $n = $stream.Read($hdr, $got, 4 - $got); if ($n -le 0) { throw 'RCON connection closed' }; $got += $n }
		$len = [BitConverter]::ToInt32($hdr, 0); $buf = New-Object byte[] $len; $got = 0
		while ($got -lt $len) { $n = $stream.Read($buf, $got, $len - $got); if ($n -le 0) { break }; $got += $n }
		[pscustomobject]@{ Id = [BitConverter]::ToInt32($buf, 0); Body = [System.Text.Encoding]::UTF8.GetString($buf, 8, [Math]::Max(0, $len - 10)) }
	}
	try {
		Write-Packet 1 3 $script:RconPassword
		if ((Read-Packet).Id -ne 1) { throw 'RCON authentication failed' }
		Write-Packet 2 2 $Command
		return ((Read-Packet).Body -replace '\x1b\[[0-9;]*m', '')
	} finally { $client.Dispose() }
}

function Stop-SlipwayE2EServer {
	param([Parameter(Mandatory)]$Server, [int]$TimeoutSeconds = 90)
	if (-not (Get-Process -Id $Server.ProcessId -ErrorAction SilentlyContinue)) { return $true }
	try { Send-SlipwayE2ERcon -Command 'stop' -TimeoutMilliseconds 5000 | Out-Null } catch { }
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	while ((Get-Date) -lt $deadline -and (Get-Process -Id $Server.ProcessId -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 500 }
	if (Get-Process -Id $Server.ProcessId -ErrorAction SilentlyContinue) { Stop-Process -Id $Server.ProcessId -Force }
	-not (Get-Process -Id $Server.ProcessId -ErrorAction SilentlyContinue)
}

function Get-SlipwayE2ELogOffset { param([Parameter(Mandatory)][string]$LogPath) if (Test-Path $LogPath) { (Get-Item $LogPath).Length } else { 0 } }

function Read-SlipwayE2ELogSince {
	param([Parameter(Mandatory)][string]$LogPath, [long]$Offset = 0)
	if (-not (Test-Path $LogPath)) { return @() }
	$fs = [System.IO.File]::Open($LogPath, 'Open', 'Read', 'ReadWrite')
	try {
		if ($Offset -gt $fs.Length) { $Offset = 0 }
		$fs.Seek($Offset, 'Begin') | Out-Null
		$text = (New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)).ReadToEnd()
	} finally { $fs.Dispose() }
	if ([string]::IsNullOrEmpty($text)) { return @() }
	@($text -split "`r?`n" | Where-Object { $_ -ne '' })
}

function Wait-SlipwayE2ELog {
	param([Parameter(Mandatory)][string]$LogPath, [Parameter(Mandatory)][string]$Pattern, [long]$StartOffset = 0, [int]$TimeoutSeconds = 60)
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	do {
		$hits = @(Read-SlipwayE2ELogSince -LogPath $LogPath -Offset $StartOffset | Where-Object { $_ -match $Pattern })
		if ($hits.Count -gt 0) { return $hits }
		Start-Sleep -Milliseconds 400
	} while ((Get-Date) -lt $deadline)
	throw "Timed out after ${TimeoutSeconds}s waiting for /$Pattern/ in $LogPath"
}

# Log lines that are known, harmless and not caused by Slipway (checked by hand; see DESIGN.md "Known log noise").
$script:KnownLogNoise = @(
	'agent command failed',                           # deliberate negative probes by the harness
	'Reference map .* could not be read',             # Iris/Sodium dev refmaps
	'Requested post effect does not exist',           # vanilla 26.3 with Iris
	'Distant Horizons OpenGL error logging',          # DH informational
	'Force-disabling mixin',                          # Sodium/Iris rule
	'Sodium has applied one or more workarounds',     # driver workaround notice
	'This is not necessarily an issue',
	'Rejected helm control',                          # forged-packet scenario expects these
	# Iris's Distant Horizons compat, once when shaders come on; also in the user's Slipway-free Tellus-Expeditions log.
	'Unexpected; somehow the Opaque \+ Translucent pass ran with shaders on',
	# A quitting singleplayer client closes the in-memory channel while the integrated server is still writing to it
	# (seen in 1 of 5 quits in the leak test, before Slipway stopped sending to closing connections; any packet written
	# in that window, vanilla's included, ends the connection this way instead of "Disconnected"). Benign at quit time.
	'lost connection: Internal Exception: java\.nio\.channels\.ClosedChannelException',
	# Vanilla's renderer when the window is minimized (e.g. by a Remote Desktop session change on this machine):
	# "Couldn't acquire next surface texture ... SurfaceException: Cannot acquire minimized window".
	"Couldn't acquire next surface texture",
	'SurfaceException: Cannot acquire minimized window'
)

function Get-SlipwayE2ELogProblems {
	<# .SYNOPSIS Error, exception and mixin-failure lines (and any warning naming Slipway) since an offset, minus known noise. #>
	param([Parameter(Mandatory)][string]$LogPath, [long]$Offset = 0, [string[]]$AlsoIgnore = @())
	$ignore = @($script:KnownLogNoise + $AlsoIgnore)
	@(Read-SlipwayE2ELogSince -LogPath $LogPath -Offset $Offset | Where-Object {
		$line = $_
		($line -match '/(ERROR|FATAL)\]|Exception|Mixin apply|InvalidInjection|InjectionError|mixin.*[Ff]ailed' -or ($line -match '/WARN\]' -and $line -match '(?i)slipway')) -and
			-not ($ignore | Where-Object { $line -match $_ })
	})
}

# ---------------------------------------------------------------------------------------------------------------
# Prism test instances
# ---------------------------------------------------------------------------------------------------------------

function Get-SlipwayE2EInstanceDir { param([string]$InstanceId = $script:InstanceA) Join-Path $script:PrismRoot "instances\$InstanceId" }

function New-SlipwayE2EInstance {
	<# .SYNOPSIS Creates a Prism instance for Minecraft 26.3 with Fabric loader 0.19.5 (does nothing if it exists). #>
	[CmdletBinding()]
	param([Parameter(Mandatory)][string]$InstanceId, [Parameter(Mandatory)][string]$DisplayName, [int]$MaxMemoryMb = 8192,
		[string]$Notes = 'Slipway end-to-end test instance (carries the test-only agent mod).')
	$dir = Get-SlipwayE2EInstanceDir $InstanceId
	if (Test-Path (Join-Path $dir 'instance.cfg')) { return $dir }
	New-Item -ItemType Directory -Force (Join-Path $dir '.minecraft\mods'), (Join-Path $dir '.minecraft\config'), (Join-Path $dir '.minecraft\shaderpacks') | Out-Null
	$cfg = @(
		'[General]', 'ConfigVersion=1.2', 'InstanceType=OneSix', 'IgnoreJavaCompatibility=true', 'JavaArchitecture=64',
		"JavaPath=$($script:JavaW.Replace('\','/'))", 'JavaRealArchitecture=amd64', 'JavaVendor=Eclipse Adoptium', 'JavaVersion=25.0.3',
		'JvmArgs=-XX:+UseZGC --enable-native-access=ALL-UNNAMED', "MaxMemAlloc=$MaxMemoryMb", 'MinMemAlloc=1024', 'OverrideJavaArgs=true', 'OverrideJavaLocation=true',
		'OverrideMemory=true', 'OverrideJava=false', 'ManagedPack=false', 'iconKey=default', "name=$DisplayName", "notes=$Notes",
		'LaunchMaximized=false', 'MinecraftWinWidth=1280', 'MinecraftWinHeight=720', 'OverrideWindow=true', 'QuitAfterGameStop=false',
		'ShowConsole=false', 'ShowConsoleOnError=false', 'AutoCloseConsole=false', 'CloseAfterLaunch=false'
	)
	Set-Content -Path (Join-Path $dir 'instance.cfg') -Value $cfg -Encoding UTF8
	$pack = [ordered]@{
		components = @(
			[ordered]@{ cachedName = 'LWJGL 3'; cachedVersion = '3.4.3'; cachedVolatile = $true; dependencyOnly = $true; uid = 'org.lwjgl3'; version = '3.4.3' },
			[ordered]@{ cachedName = 'Minecraft'; cachedRequires = @(@{ suggests = '3.4.3'; uid = 'org.lwjgl3' }); cachedVersion = '26.3'; important = $true; uid = 'net.minecraft'; version = '26.3' },
			[ordered]@{ cachedName = 'Intermediary Mappings'; cachedRequires = @(@{ equals = '26.3'; uid = 'net.minecraft' }); cachedVersion = '26.3'; cachedVolatile = $true; dependencyOnly = $true; uid = 'net.fabricmc.intermediary'; version = '26.3' },
			[ordered]@{ cachedName = 'Fabric Loader'; cachedRequires = @(@{ uid = 'net.fabricmc.intermediary' }); cachedVersion = '0.19.5'; uid = 'net.fabricmc.fabric-loader'; version = '0.19.5' }
		)
		formatVersion = 1
	}
	$pack | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $dir 'mmc-pack.json') -Encoding UTF8
	$dir
}

function Set-SlipwayE2EInstanceMods {
	<# .SYNOPSIS Replaces an instance's mods with Fabric API, Sodium, Iris, Distant Horizons, Slipway and (optionally) the agent. #>
	[CmdletBinding()]
	param([string]$InstanceId = $script:InstanceA, [Parameter(Mandatory)][string]$SlipwayJar, [string]$AgentJar, [switch]$NoRenderMods)
	if ($script:DesktopHelper -and (Get-Command Get-CopilotPrismMinecraftProcess -ErrorAction SilentlyContinue) -and (Get-CopilotPrismMinecraftProcess -InstanceId $InstanceId)) {
		throw "Prism instance '$InstanceId' is running; stop it before replacing mods."
	}
	$mods = Join-Path (Get-SlipwayE2EInstanceDir $InstanceId) '.minecraft\mods'
	New-Item -ItemType Directory -Force $mods | Out-Null
	Get-ChildItem $mods -Filter *.jar | Remove-Item -Force
	$devmods = Join-Path $script:Repo 'devmods'
	Copy-Item (Join-Path $devmods 'fabric-api-0.160.7+26.3.jar') $mods
	if (-not $NoRenderMods) {
		Copy-Item (Join-Path $devmods 'sodium-fabric-0.9.2+mc26.3.jar') $mods
		Copy-Item (Join-Path $devmods 'iris-fabric-1.11.6+mc26.3.jar') $mods
		Copy-Item (Join-Path $devmods 'DistantHorizons-fabric-3.3.1-tellus-fork.6-26.3.jar') $mods
	}
	Copy-Item $SlipwayJar $mods
	if ($AgentJar) { Copy-Item $AgentJar $mods }
	Get-ChildItem $mods -Filter *.jar | ForEach-Object { [pscustomobject]@{ Jar = $_.Name; Sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash } }
}

function Set-SlipwayE2EShaders {
	<# .SYNOPSIS Selects Bliss in Iris and turns shaders on or off (takes effect on the next launch or 'iris on|off'). #>
	param([string]$InstanceId = $script:InstanceA, [bool]$Enabled = $true)
	$mc = Join-Path (Get-SlipwayE2EInstanceDir $InstanceId) '.minecraft'
	$source = Join-Path $script:PrismRoot 'instances\Tellus-Expeditions-MC-26.3-Fabric\.minecraft\shaderpacks\Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip'
	New-Item -ItemType Directory -Force (Join-Path $mc 'shaderpacks'), (Join-Path $mc 'config') | Out-Null
	$target = Join-Path $mc 'shaderpacks\Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip'
	if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $source -Destination $target }
	@(
		'allowUnknownShaders=false', 'colorSpace=SRGB', 'disableUpdateMessage=true', 'enableDebugOptions=false',
		"enableShaders=$($Enabled.ToString().ToLower())", 'maxShadowRenderDistance=32', 'shaderPack=Bliss_v2.1.2_(Chocapic13_Shaders_edit).zip'
	) | Set-Content -Path (Join-Path $mc 'config\iris.properties') -Encoding ASCII
}

function Set-SlipwayE2EOptions {
	<# .SYNOPSIS Writes a predictable options.txt for a test instance (render distance, GUI scale, no tutorials). #>
	param([string]$InstanceId = $script:InstanceA, [int]$RenderDistance = 10, [int]$Fov = 70, [string]$GameDir)
	$mc = if ($GameDir) { $GameDir } else { Join-Path (Get-SlipwayE2EInstanceDir $InstanceId) '.minecraft' }
	$fovValue = ($Fov - 70) / 40.0
	@(
		"renderDistance:$RenderDistance", 'simulationDistance:8', 'guiScale:2', "fov:$fovValue", 'maxFps:120', 'enableVsync:false',
		'tutorialStep:none', 'skipMultiplayerWarning:true', 'onboardAccessibility:false', 'joinedFirstServer:true', 'pauseOnLostFocus:false',
		'narrator:0', 'soundCategory_master:0.0', 'ao:true', 'entityDistanceScaling:1.0', 'fullscreen:false', 'inactivityFpsLimit:"minimized"'
	) | Set-Content -Path (Join-Path $mc 'options.txt') -Encoding ASCII
}

function Install-SlipwayE2EAgent {
	<# .SYNOPSIS Builds the test-only agent mod and returns its jar path. #>
	& (Join-Path $script:Repo 'gradlew.bat') -p (Join-Path $script:Repo 'tools\e2e\agent') build '-Dorg.gradle.console=plain' | Out-Null
	if ($LASTEXITCODE -ne 0) { throw 'Building the Slipway e2e agent failed' }
	(Get-ChildItem (Join-Path $script:Repo 'tools\e2e\agent\build\libs') -Filter 'slipway-e2e-agent-*.jar' | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1).FullName
}

# ---------------------------------------------------------------------------------------------------------------
# Clients
# ---------------------------------------------------------------------------------------------------------------

function Set-SlipwayE2EClientBackground {
	<# .SYNOPSIS Keeps the game window behind the user's windows without ever activating it. #>
	param([Parameter(Mandatory)]$Client)
	$info = Get-CopilotWindowInfo -ProcessId $Client.ProcessId
	$hwnd = [IntPtr]$info.Handle
	if ([SlipwayE2ENative]::GetForegroundWindow() -eq $hwnd) {
		[SlipwayE2ENative]::ShowWindow($hwnd, 6) | Out-Null
		Start-Sleep -Milliseconds 400
		[SlipwayE2ENative]::ShowWindow($hwnd, 4) | Out-Null
		Start-Sleep -Milliseconds 200
	} elseif ([SlipwayE2ENative]::IsIconic($hwnd)) {
		[SlipwayE2ENative]::ShowWindow($hwnd, 4) | Out-Null
		Start-Sleep -Milliseconds 200
	}
	[SlipwayE2ENative]::SetWindowPos($hwnd, [IntPtr]1, 0, 0, 0, 0, [uint32](0x0001 -bor 0x0002 -bor 0x0010)) | Out-Null
}

function Invoke-SlipwayE2EAgent {
	<# .SYNOPSIS Sends one command to a client's agent and returns its answer (throws on 'error' or timeout). #>
	[CmdletBinding()]
	param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][string]$Verb, [string]$Argument = '', [int]$TimeoutSeconds = 30)
	$directory = Join-Path $Client.GameDir 'slipway-agent'
	$results = Join-Path $directory 'results.txt'
	$id = "c$([guid]::NewGuid().ToString('N').Substring(0, 12))"
	$line = (@($id, $Verb, $Argument) | Where-Object { $_ -ne '' }) -join ' '
	$offset = if (Test-Path $results) { (Get-Item $results).Length } else { 0 }
	[System.IO.File]::AppendAllText((Join-Path $directory 'commands.txt'), $line + "`n", [System.Text.UTF8Encoding]::new($false))
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	do {
		if (Test-Path $results) {
			$fs = [System.IO.File]::Open($results, 'Open', 'Read', 'ReadWrite')
			try { $fs.Seek([Math]::Min($offset, $fs.Length), 'Begin') | Out-Null; $appended = (New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)).ReadToEnd() } finally { $fs.Dispose() }
			$whole = $appended.Substring(0, $appended.LastIndexOf("`n") + 1)
			$answer = $whole -split "`r?`n" | Where-Object { $_ -like "$id *" } | Select-Object -First 1
			if ($answer) {
				$answer = $answer.Substring($id.Length + 1)
				if ($answer -like 'error*') { throw "Agent refused '$Verb $Argument': $answer" }
				return ($answer -replace '^ok ?', '')
			}
		}
		Start-Sleep -Milliseconds 50
	} while ((Get-Date) -lt $deadline)
	throw "The agent did not answer '$Verb $Argument' within ${TimeoutSeconds}s"
}

function Confirm-SlipwayE2EPrismOfflinePrompt {
	<#
	.SYNOPSIS When Prism could not refresh the Microsoft account it asks for an offline player name before launching.
	The e2e server runs in offline mode, so the harness accepts the suggested name (the account's own) by posting Enter
	to that dialog, without activating any window. Returns $true when it answered a prompt.
	#>
	$answered = $false
	foreach ($p in Get-Process prismlauncher -ErrorAction SilentlyContinue) {
		$p.Refresh()
		if ($p.MainWindowTitle -match '^Player name' -and $p.MainWindowHandle -ne [IntPtr]::Zero) {
			Send-CopilotKeyMessage -WindowHandle $p.MainWindowHandle -VirtualKey 0x0D -KeyDown $true
			Start-Sleep -Milliseconds 60
			Send-CopilotKeyMessage -WindowHandle $p.MainWindowHandle -VirtualKey 0x0D -KeyDown $false
			Write-Host "Prism asked for an offline player name (account refresh failed); accepted the suggested name."
			$answered = $true
			Start-Sleep -Seconds 1
		}
	}
	$answered
}

function Start-SlipwayE2EClient {
	<# .SYNOPSIS Launches a Prism test instance joined to the e2e server and waits until the player is in the world. #>
	[CmdletBinding()]
	param($Server, [string]$InstanceId = $script:InstanceA, [string]$Profile, [int]$TimeoutSeconds = 300)
	$paths = Get-CopilotPrismInstancePaths -InstanceId $InstanceId -PrismRoot $script:PrismRoot
	$gameDir = Join-Path (Get-SlipwayE2EInstanceDir $InstanceId) '.minecraft'
	if (Get-CopilotPrismMinecraftProcess -InstanceId $InstanceId) { throw "Prism instance '$InstanceId' is already running" }
	$serverOffset = if ($Server) { Get-SlipwayE2ELogOffset -LogPath $Server.LogPath } else { 0 }
	$started = Get-Date
	$launchArgs = @('--launch', $InstanceId)
	if ($Server) { $launchArgs += @('--server', "localhost:$($Server.Port)") }
	if ($Profile) { $launchArgs += @('--profile', $Profile) }
	Start-Process -FilePath $script:PrismExe -ArgumentList $launchArgs | Out-Null
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	$game = $null
	while (-not $game -and (Get-Date) -lt $deadline) {
		Start-Sleep -Milliseconds 500
		Confirm-SlipwayE2EPrismOfflinePrompt | Out-Null
		$game = Get-CopilotPrismMinecraftProcess -InstanceId $InstanceId -StartedAfter $started
	}
	if (-not $game) { throw "Prism did not start '$InstanceId' within ${TimeoutSeconds}s" }
	$client = [pscustomobject]@{ InstanceId = $InstanceId; ProcessId = $game.ProcessId; GameDir = $gameDir; ClientLog = $paths.LatestLog; Player = $null }
	if ($Server) {
		$joined = $null
		while (-not $joined -and (Get-Date) -lt $deadline) {
			try { Set-SlipwayE2EClientBackground -Client $client | Out-Null } catch { }
			try { $joined = Wait-SlipwayE2ELog -LogPath $Server.LogPath -Pattern '(\S+) joined the game' -StartOffset $serverOffset -TimeoutSeconds 2 } catch { }
			if (-not (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue)) { throw 'The game exited before joining' }
		}
		if (-not $joined) { throw "No player joined within ${TimeoutSeconds}s" }
		$client.Player = ([regex]::Match(@($joined)[-1], '(\S+) joined the game')).Groups[1].Value
		Send-SlipwayE2ERcon -Command "op $($client.Player)" | Out-Null
		$ready = $false
		while (-not $ready -and (Get-Date) -lt $deadline) {
			try {
				$where = Invoke-SlipwayE2EAgent -Client $client -Verb session -TimeoutSeconds 5
				$screen = Invoke-SlipwayE2EAgent -Client $client -Verb screen -TimeoutSeconds 5
				$ready = $where -like "world * player=$($client.Player)" -and $screen -eq 'none'
			} catch { }
			if (-not $ready) { Start-Sleep -Milliseconds 500 }
		}
		if (-not $ready) { throw 'The client did not finish loading the world' }
	}
	try { Set-SlipwayE2EClientBackground -Client $client | Out-Null } catch { }
	$client
}

function Get-SlipwayE2EWatcherProcess {
	Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" |
		Where-Object { $_.CommandLine -match 'slipway\.e2e\.watcher=true' } | Select-Object -First 1
}

function Start-SlipwayE2EWatcher {
	<#
	.SYNOPSIS Starts a second player as an offline Loom dev client (the e2eWatcher run in build.gradle: vanilla
	renderer, game directory build\e2e-watcher, player name SlipwayWatcher) and waits until it is in the world. Prism
	8.3 can only launch with a Microsoft account, and a second one is not always logged in; the e2e server runs in
	offline mode, so an offline dev client is an equally real second player.
	#>
	param([Parameter(Mandatory)]$Server, [int]$TimeoutSeconds = 300)
	if (Get-SlipwayE2EWatcherProcess) { throw 'The watcher client is already running' }
	$gameDir = Join-Path $script:Repo 'build\e2e-watcher'
	New-Item -ItemType Directory -Force (Join-Path $gameDir 'mods') | Out-Null
	$agent = Get-ChildItem (Join-Path $script:Repo 'tools\e2e\agent\build\libs') -Filter 'slipway-e2e-agent-*.jar' | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1
	if (-not $agent) { throw 'Build the e2e agent first (deploy-test-build.ps1)' }
	Get-ChildItem (Join-Path $gameDir 'mods') -Filter '*.jar' | Remove-Item -Force
	Copy-Item $agent.FullName (Join-Path $gameDir 'mods') -Force
	Set-SlipwayE2EOptions -GameDir $gameDir
	Remove-Item (Join-Path $gameDir 'slipway-e2e') -Recurse -Force -ErrorAction SilentlyContinue
	$serverOffset = Get-SlipwayE2ELogOffset -LogPath $Server.LogPath
	if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Split-Path (Split-Path $script:JavaExe) }
	$log = Join-Path $gameDir 'gradle-run.log'
	Start-Process -FilePath (Join-Path $script:Repo 'gradlew.bat') -ArgumentList @('runE2eWatcher', '--console=plain') -WorkingDirectory $script:Repo `
		-WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError "$log.err" | Out-Null
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	$game = $null
	while (-not $game -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 1; $game = Get-SlipwayE2EWatcherProcess }
	if (-not $game) { throw "The watcher dev client did not start within ${TimeoutSeconds}s (see $log)" }
	$client = [pscustomobject]@{ InstanceId = 'e2e-watcher (dev client)'; ProcessId = [int]$game.ProcessId; GameDir = $gameDir; ClientLog = (Join-Path $gameDir 'logs\latest.log'); Player = $null }
	$joined = $null
	while (-not $joined -and (Get-Date) -lt $deadline) {
		try { Set-SlipwayE2EClientBackground -Client $client | Out-Null } catch { }
		try { $joined = Wait-SlipwayE2ELog -LogPath $Server.LogPath -Pattern 'SlipwayWatcher joined the game' -StartOffset $serverOffset -TimeoutSeconds 2 } catch { }
		if (-not (Get-Process -Id $client.ProcessId -ErrorAction SilentlyContinue)) { throw "The watcher exited before joining (see $log)" }
	}
	if (-not $joined) { throw "The watcher did not join within ${TimeoutSeconds}s" }
	$client.Player = 'SlipwayWatcher'
	Send-SlipwayE2ERcon -Command "op $($client.Player)" | Out-Null
	$ready = $false
	while (-not $ready -and (Get-Date) -lt $deadline) {
		try {
			$where = Invoke-SlipwayE2EAgent -Client $client -Verb session -TimeoutSeconds 5
			$screen = Invoke-SlipwayE2EAgent -Client $client -Verb screen -TimeoutSeconds 5
			$ready = $where -like "world * player=$($client.Player)" -and $screen -eq 'none'
		} catch { }
		if (-not $ready) { Start-Sleep -Milliseconds 500 }
	}
	if (-not $ready) { throw 'The watcher did not finish loading the world' }
	try { Set-SlipwayE2EClientBackground -Client $client | Out-Null } catch { }
	$client
}

function Stop-SlipwayE2EClient {
	param([Parameter(Mandatory)]$Client, [int]$TimeoutSeconds = 180)
	if (Get-Process -Id $Client.ProcessId -ErrorAction SilentlyContinue) {
		try { Close-CopilotWindowGracefully -ProcessId $Client.ProcessId -TimeoutSeconds $TimeoutSeconds | Out-Null } catch { }
	}
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	while ((Get-Date) -lt $deadline -and (Get-Process -Id $Client.ProcessId -ErrorAction SilentlyContinue)) { Start-Sleep -Seconds 1 }
	-not (Get-Process -Id $Client.ProcessId -ErrorAction SilentlyContinue)
}

function Stop-SlipwayE2EAll {
	param($Server, [object[]]$Clients = @())
	foreach ($c in $Clients) { if ($c) { try { Invoke-SlipwayE2EAgent -Client $c -Verb disconnect -TimeoutSeconds 20 | Out-Null } catch { } } }
	$result = [ordered]@{}
	foreach ($c in $Clients) { if ($c) { $result["client $($c.InstanceId)"] = Stop-SlipwayE2EClient -Client $c } }
	if ($Server) { $result.server = Stop-SlipwayE2EServer -Server $Server }
	[pscustomobject]$result
}

function Wait-SlipwayE2ETerrain {
	<# .SYNOPSIS Waits until the client's terrain renderer has had no section build queued for StableMilliseconds; false on timeout. #>
	param([Parameter(Mandatory)]$Client, [int]$StableMilliseconds = 1000, [int]$TimeoutSeconds = 20)
	# The queue can be empty for a moment between chunk batches, so one reading is not enough.
	$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
	$since = $null
	while ((Get-Date) -lt $deadline) {
		if ([string](Invoke-SlipwayE2EAgent -Client $Client -Verb terrain) -match 'complete=true') {
			if (-not $since) { $since = Get-Date }
			if (((Get-Date) - $since).TotalMilliseconds -ge $StableMilliseconds) { return $true }
		} else {
			$since = $null
		}
		Start-Sleep -Milliseconds 100
	}
	$false
}

function Save-SlipwayE2EScreenshot {
	<# .SYNOPSIS Captures the game's own frame to <Directory>\<Name>.png. #>
	param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][string]$Directory, [switch]$HideHud, [int]$SettleMilliseconds = 0,
		[int]$TerrainTimeoutSeconds = 20)
	if ($SettleMilliseconds -gt 0) { Start-Sleep -Milliseconds $SettleMilliseconds }
	# A client that just joined or moved has many terrain sections to build, and until its queue drains, frames miss
	# recent block changes (a freshly started client needed 6 to 12 s before blocks placed next to it were drawn).
	if ($TerrainTimeoutSeconds -gt 0 -and -not (Wait-SlipwayE2ETerrain -Client $Client -TimeoutSeconds $TerrainTimeoutSeconds)) {
		Write-Host "note: terrain still building after $TerrainTimeoutSeconds s; screenshot $Name may miss recent block changes"
	}
	New-Item -ItemType Directory -Force $Directory | Out-Null
	$path = Join-Path $Directory ("{0}.png" -f ($Name -replace '[^\w\-\.]', '_'))
	if ($HideHud) { Invoke-SlipwayE2EAgent -Client $Client -Verb hud -Argument hide | Out-Null; Start-Sleep -Milliseconds 300 }
	try {
		$file = "e2e-$([guid]::NewGuid().ToString('N')).png"
		Invoke-SlipwayE2EAgent -Client $Client -Verb screenshot -Argument $file -TimeoutSeconds 30 | Out-Null
		$source = Join-Path $Client.GameDir "screenshots\$file"
		Copy-Item $source $path -Force
		Remove-Item $source -Force -ErrorAction SilentlyContinue
	} finally {
		if ($HideHud) { Invoke-SlipwayE2EAgent -Client $Client -Verb hud -Argument show | Out-Null }
	}
	$path
}

# ---------------------------------------------------------------------------------------------------------------
# Game helpers
# ---------------------------------------------------------------------------------------------------------------

function Get-SlipwayE2EVessel {
	<# .SYNOPSIS Parses '/slipway info <id>' into an object. #>
	param([Parameter(Mandatory)][long]$Id)
	$text = Send-SlipwayE2ERcon -Command "slipway info $Id"
	$o = [ordered]@{ Text = $text }
	foreach ($m in [regex]::Matches($text, '(\w+)=([^\s]+)')) { $o[$m.Groups[1].Value] = $m.Groups[2].Value }
	if ($o.Contains('pos')) { $p = $o.pos -split ','; $o.X = [double]$p[0]; $o.Y = [double]$p[1]; $o.Z = [double]$p[2] }
	if ($o.Contains('centre')) { $p = $o.centre -split ','; $o.CX = [double]$p[0]; $o.CY = [double]$p[1]; $o.CZ = [double]$p[2] }
	if ($o.Contains('q')) { $o.Q = @($o.q -split ',' | ForEach-Object { [double]$_ }) }
	if ($o.Contains('vel')) { $o.Velocity = @($o.vel -split ',' | ForEach-Object { [double]$_ }) }
	if ($o.Contains('plotAnchor')) { $o.PlotAnchor = @($o.plotAnchor -split ',' | ForEach-Object { [long]$_ }) }
	foreach ($k in 'pitch', 'yaw', 'roll', 'tilt', 'speed', 'spin', 'mass') { if ($o.Contains($k)) { $o[$k] = [double]$o[$k] } }
	[pscustomobject]$o
}

function ConvertTo-SlipwayE2EWorld {
	<# .SYNOPSIS World position of a vessel-local point, from a vessel object returned by Get-SlipwayE2EVessel. #>
	param([Parameter(Mandatory)]$Vessel, [double]$X, [double]$Y, [double]$Z)
	$qx, $qy, $qz, $qw = $Vessel.Q
	# v' = v + 2w(q x v) + 2 q x (q x v)
	$cx = $qy * $Z - $qz * $Y; $cy = $qz * $X - $qx * $Z; $cz = $qx * $Y - $qy * $X
	$ccx = $qy * $cz - $qz * $cy; $ccy = $qz * $cx - $qx * $cz; $ccz = $qx * $cy - $qy * $cx
	[pscustomobject]@{ X = $Vessel.X + $X + 2 * ($qw * $cx + $ccx); Y = $Vessel.Y + $Y + 2 * ($qw * $cy + $ccy); Z = $Vessel.Z + $Z + 2 * ($qw * $cz + $ccz) }
}

function Get-SlipwayE2EBlockData {
	<# .SYNOPSIS A block entity's data without its position, or $null when there is none. #>
	param([Parameter(Mandatory)][long]$X, [Parameter(Mandatory)][long]$Y, [Parameter(Mandatory)][long]$Z)
	$text = [string](Send-SlipwayE2ERcon -Command "data get block $X $Y $Z")
	if ($text -notmatch 'has the following block data: (.*)$') { return $null }
	(($matches[1] -replace '\b[xyz]: -?\d+, ?', '') -replace ', [xyz]: -?\d+(?=[,}])', '')
}

function Get-SlipwayE2EPlayerPosition {
	param([Parameter(Mandatory)][string]$Player)
	$pos = [string](Send-SlipwayE2ERcon -Command "data get entity $Player Pos")
	if ($pos -notmatch '\[\s*(-?[\d.E-]+)d,\s*(-?[\d.E-]+)d,\s*(-?[\d.E-]+)d\s*\]') { throw "Could not read the player position: $pos" }
	[pscustomobject]@{ X = [double]$matches[1]; Y = [double]$matches[2]; Z = [double]$matches[3] }
}

function Test-SlipwayE2EBlock {
	<# .SYNOPSIS True when the block at x y z matches a block predicate (execute if block). #>
	param([Parameter(Mandatory)][long]$X, [Parameter(Mandatory)][long]$Y, [Parameter(Mandatory)][long]$Z, [Parameter(Mandatory)][string]$Block)
	[string](Send-SlipwayE2ERcon -Command "execute if block $X $Y $Z $Block") -match 'passed'
}

# ---------------------------------------------------------------------------------------------------------------
# Runs, reports and validation.json
# ---------------------------------------------------------------------------------------------------------------

function New-SlipwayE2ERun {
	param([Parameter(Mandatory)][string]$Name)
	$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
	$dir = Join-Path $script:Root "runs\$Name-$stamp"
	New-Item -ItemType Directory -Force $dir | Out-Null
	$jar = Get-ChildItem (Join-Path (Get-SlipwayE2EServerDir) 'mods') -Filter 'slipway-fabric-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
	$sha = if ($jar) { (Get-FileHash $jar.FullName -Algorithm SHA256).Hash } else { '' }
	[pscustomobject]@{ Name = $Name; Id = "$Name-$stamp"; Directory = $dir; Steps = [System.Collections.Generic.List[object]]::new(); StartedAt = Get-Date
		Notes = [System.Collections.Generic.List[string]]::new(); JarSha256 = $sha }
}

function Add-SlipwayE2ECheck {
	<# .SYNOPSIS Records a pass/fail step from a condition and returns the condition. #>
	param([Parameter(Mandatory)]$Run, [Parameter(Mandatory)][string]$Title, [Parameter(Mandatory)][bool]$Condition, [string]$Detail = '', [string]$Screenshot = '')
	Add-SlipwayE2EStep -Run $Run -Title $Title -Detail $Detail -Screenshot $Screenshot -Status $(if ($Condition) { 'pass' } else { 'fail' })
	$Condition
}

function Add-SlipwayE2EStep {
	param([Parameter(Mandatory)]$Run, [Parameter(Mandatory)][string]$Title, [string]$Detail = '', [string]$Screenshot = '', [ValidateSet('pass', 'fail', 'info')][string]$Status = 'info')
	$Run.Steps.Add([pscustomobject]@{ At = (Get-Date).ToString('HH:mm:ss'); Title = $Title; Detail = $Detail; Screenshot = $Screenshot; Status = $Status })
	Write-Host ("[{0}] {1} {2}" -f $Status.ToUpper(), $Title, $Detail)
}

function Save-SlipwayE2EReport {
	<# .SYNOPSIS Writes report.md into the run folder and returns the overall result (pass only if every check passed). #>
	param([Parameter(Mandatory)]$Run, [string[]]$ExtraLogs = @())
	$failed = @($Run.Steps | Where-Object { $_.Status -eq 'fail' }).Count
	$passed = @($Run.Steps | Where-Object { $_.Status -eq 'pass' }).Count
	$result = if ($failed -eq 0 -and $passed -gt 0) { 'pass' } else { 'fail' }
	$md = [System.Collections.Generic.List[string]]::new()
	$md.Add("# Slipway e2e run: $($Run.Name)"); $md.Add('')
	$md.Add("Run id $($Run.Id), started $($Run.StartedAt.ToString('u')), result **$result** ($passed passed, $failed failed)."); $md.Add('')
	foreach ($n in $Run.Notes) { $md.Add("- $n") }
	$md.Add(''); $md.Add('| time | status | step | detail | screenshot |'); $md.Add('| --- | --- | --- | --- | --- |')
	foreach ($s in $Run.Steps) {
		$shot = if ($s.Screenshot) { "[$(Split-Path $s.Screenshot -Leaf)]($(Split-Path $s.Screenshot -Leaf))" } else { '' }
		$md.Add("| $($s.At) | $($s.Status) | $($s.Title) | $(($s.Detail -replace '\|', '\|') -replace "`r?`n", ' ') | $shot |")
	}
	Set-Content -Path (Join-Path $Run.Directory 'report.md') -Value $md -Encoding UTF8
	foreach ($log in $ExtraLogs) { if ($log -and (Test-Path $log)) { Copy-Item $log $Run.Directory -Force } }
	$result
}

function Add-SlipwayValidation {
	<# .SYNOPSIS Appends a run to validation.json in the repository (scenario, result, run id, evidence). #>
	param([Parameter(Mandatory)]$Run, [Parameter(Mandatory)][string]$Result, [string]$Scenario = $Run.Name, [string]$Milestone = '')
	$file = Join-Path $script:Repo 'validation.json'
	$data = if (Test-Path $file) { Get-Content $file -Raw | ConvertFrom-Json } else { [pscustomobject]@{ schemaVersion = 1; runs = @() } }
	$evidence = @(Get-ChildItem $Run.Directory -File | ForEach-Object { $_.FullName })
	$commit = (git -C $script:Repo rev-parse --short HEAD 2>$null)
	# validation.json itself changes with every run; any other uncommitted change marks the run as dirty.
	if (git -C $script:Repo status --porcelain -- . ':(exclude)validation.json' 2>$null) { $commit = "$commit+dirty" }
	# Runs with screenshots only pass once a person (or the agent, looking at them) has reviewed the images.
	$hasShots = @($Run.Steps | Where-Object { $_.Screenshot }).Count -gt 0
	$entry = [pscustomobject]@{
		scenario = $Scenario; result = $(if ($Result -eq 'pass' -and $hasShots) { 'pending-review' } else { $Result }); automatedResult = $Result
		visualReview = $null; runId = $Run.Id; milestone = $Milestone; date = (Get-Date).ToString('s')
		commit = $commit; slipwayJarSha256 = $Run.JarSha256; report = (Join-Path $Run.Directory 'report.md'); evidence = $evidence
		checks = @($Run.Steps | Where-Object { $_.Status -ne 'info' } | ForEach-Object { "$($_.Status): $($_.Title)" })
	}
	$data.runs = @($data.runs) + $entry
	$data | ConvertTo-Json -Depth 6 | Set-Content $file -Encoding UTF8
	$entry
}

function Set-SlipwayValidationReview {
	<# .SYNOPSIS Records the visual review of a run's screenshots; the run passes only if its checks passed too. #>
	param([Parameter(Mandatory)][string]$RunId, [Parameter(Mandatory)][ValidateSet('pass', 'fail')][string]$Verdict, [Parameter(Mandatory)][string]$Notes)
	$file = Join-Path $script:Repo 'validation.json'
	$data = Get-Content $file -Raw | ConvertFrom-Json
	$entry = @($data.runs | Where-Object { $_.runId -eq $RunId })[0]
	if (-not $entry) { throw "No run $RunId in validation.json" }
	$entry.visualReview = "${Verdict}: $Notes"
	$entry.result = if ($entry.automatedResult -eq 'pass' -and $Verdict -eq 'pass') { 'pass' } else { 'fail' }
	$data | ConvertTo-Json -Depth 6 | Set-Content $file -Encoding UTF8
	Add-Content -Path $entry.report -Value @('', '## Visual review', '', "**$Verdict** - $Notes", '', "Final result: **$($entry.result)**") -Encoding UTF8
	$entry
}

Export-ModuleMember -Function *-SlipwayE2E*, Add-SlipwayValidation, Set-SlipwayValidationReview
