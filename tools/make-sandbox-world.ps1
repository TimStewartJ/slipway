# Builds the "Slipway Sandbox" world for the play instance with the e2e dedicated server: a fresh world, a glass
# spawn platform facing south, and two identical demo skiffs in front of it (366 blocks: deck, hull, keel, railings,
# mast and sail, a doorway with a door, a chest with items, a redstone lamp with a lever, a sign and a lantern).
# Seen from spawn (facing south), the one on the right (x -8) is assembled into a vessel and the one on the left (x 8)
# is plain blocks to assemble yourself. The server runs with Fabric API and the given Slipway jar (the play instance's
# jar, so the saved vessel matches it). Then copies the world
# into the play instance's saves as a creative world with cheats on. The e2e session must not be running.
param(
	[Parameter(Mandatory)][string]$SlipwayJar,
	[string]$InstanceId = 'Slipway-MC-26.3-Fabric',
	[string]$Folder = 'SlipwaySandbox',
	[string]$LevelName = 'Slipway Sandbox',
	[long]$Seed = 8675309
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e\scenarios\_common.ps1')

function Get-Skiff {
	# Relative to the helm, which faces north: the pilot stands north of it and the bow points south (+z).
	$b = [System.Collections.Generic.List[object]]::new()
	$b.Add((New-SlipwayE2EBlock 'helm' 0 0 0 'slipway:helm' ([ordered]@{ facing = 'north' })))
	$b.Add((New-SlipwayE2EBlock 'chest' -3 0 0 'minecraft:chest' ([ordered]@{ facing = 'east'; type = 'single' }) '{Items:[{Slot:0b,id:"slipway:helm",count:4},{Slot:1b,id:"minecraft:spyglass",count:1},{Slot:2b,id:"minecraft:oak_planks",count:64},{Slot:3b,id:"minecraft:glass",count:64},{Slot:4b,id:"minecraft:redstone_lamp",count:8},{Slot:5b,id:"minecraft:lever",count:8}]}'))
	$b.Add((New-SlipwayE2EBlock 'lamp' 3 0 0 'minecraft:redstone_lamp' ([ordered]@{ lit = 'false' })))
	$b.Add((New-SlipwayE2EBlock 'lever' 3 1 0 'minecraft:lever' ([ordered]@{ face = 'floor'; facing = 'north'; powered = 'false' })))
	foreach ($y in 0, 1) {
		$b.Add((New-SlipwayE2EBlock "post-w$y" -1 $y 4 'minecraft:oak_log' ([ordered]@{ axis = 'y' })))
		$b.Add((New-SlipwayE2EBlock "post-e$y" 1 $y 4 'minecraft:oak_log' ([ordered]@{ axis = 'y' })))
	}
	foreach ($x in -1..1) { $b.Add((New-SlipwayE2EBlock "lintel$x" $x 2 4 'minecraft:oak_log' ([ordered]@{ axis = 'x' }))) }
	$b.Add((New-SlipwayE2EBlock 'door-lower' 0 0 4 'minecraft:oak_door' ([ordered]@{ facing = 'south'; half = 'lower'; hinge = 'left'; open = 'false' })))
	$b.Add((New-SlipwayE2EBlock 'door-upper' 0 1 4 'minecraft:oak_door' ([ordered]@{ facing = 'south'; half = 'upper'; hinge = 'left'; open = 'false' })))
	foreach ($y in 0..5) { $b.Add((New-SlipwayE2EBlock "mast$y" 0 $y 9 'minecraft:oak_log' ([ordered]@{ axis = 'y' }))) }
	foreach ($y in 2..4) { foreach ($x in -2, -1, 1, 2) { $b.Add((New-SlipwayE2EBlock "sail$x$y" $x $y 9 'minecraft:white_wool')) } }
	$b.Add((New-SlipwayE2EBlock 'flag' 0 6 9 'minecraft:red_wool'))
	$b.Add((New-SlipwayE2EBlock 'sign' -2 0 -1 'minecraft:oak_sign' ([ordered]@{ rotation = '8' }) '{front_text:{messages:["Slipway","use the helm","to take it;","see PLAYTEST.md"]}}'))
	$b.Add((New-SlipwayE2EBlock 'lantern' 2 0 12 'minecraft:lantern' ([ordered]@{ hanging = 'false' })))
	,$b
}

function Build-Skiff([long]$X, [long]$Y, [long]$Z) {
	# Keel, hull and deck (the deck one block under the helm), then railings along the sides and the bow.
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:dark_oak_planks" -f ($X - 1), ($Y - 3), ($Z + 1), ($X + 1), ($Z + 11)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:spruce_planks" -f ($X - 3), ($Y - 2), ($Z - 1), ($X + 3), ($Z + 13)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:oak_planks" -f ($X - 4), ($Y - 1), ($Z - 2), ($X + 4), ($Z + 14)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {0} {1} {3} minecraft:oak_fence" -f ($X - 4), $Y, ($Z - 2), ($Z + 14)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {0} {1} {3} minecraft:oak_fence" -f ($X + 4), $Y, ($Z - 2), ($Z + 14)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {2} minecraft:oak_fence" -f ($X - 3), $Y, ($Z + 14), ($X + 3)) | Out-Null
	Set-SlipwayE2EBlocks -Specs (Get-Skiff) -X $X -Y $Y -Z $Z
}

function Get-SurfaceY([long]$X, [long]$Z) {
	# The highest non-air block of a column (water counts), probed downward in steps of 4 from y 256.
	for ($y = 256; $y -gt -60; $y -= 4) {
		$r = [string](Send-SlipwayE2ERcon -Command ("execute if block {0} {1} {2} minecraft:air" -f $X, $y, $Z))
		if ($r -match 'not loaded') { throw "Column $X $Z is not loaded" }
		if ($r -match 'failed') { return $y + 4 }
	}
	-60
}

if (Get-SlipwayE2EServerProcess) { throw 'The e2e server is running; stop the e2e session first (Stop-SlipwayE2ESession)' }
Set-SlipwayE2EServerMods -SlipwayJar $SlipwayJar | Out-Null
New-SlipwayE2EWorld -Name 'sandbox' | Out-Null
$server = Start-SlipwayE2EServer -World 'sandbox' -Seed $Seed
try {
	Send-SlipwayE2ERcon -Command 'forceload add -32 -32 32 32' | Out-Null
	Start-Sleep -Seconds 4
	$heights = foreach ($x in -16, 0, 16) { foreach ($z in -16, 0, 16) { Get-SurfaceY $x $z } }
	$ground = [Math]::Ceiling(($heights | Measure-Object -Maximum).Maximum)
	$y = [long][Math]::Max(80, $ground + 9)
	Write-Host "surface heights: $($heights -join ', '); ships at y $y"
	Clear-SlipwayE2EVolume -16 ($y - 4) -18 16 ($y + 8) 18
	# Spawn: a glass platform south-facing, 6 blocks behind the ships' sterns (26.3's setworldspawn takes yaw and pitch).
	Send-SlipwayE2ERcon -Command ("fill -3 {0} -15 3 {0} -9 minecraft:glass" -f ($y - 1)) | Out-Null
	Build-Skiff -8 $y 0
	Build-Skiff 8 $y 0
	$assembled = [string](Send-SlipwayE2ERcon -Command "slipway assemble -8 $y 0")
	if ($assembled -notmatch 'id=(\d+)') { throw "Assembly failed: $assembled" }
	Write-Host "demo vessel: $assembled"
	Send-SlipwayE2ERcon -Command 'forceload remove all' | Out-Null
	foreach ($cmd in "setworldspawn 0 $y -12 0 0", 'gamerule respawn_radius 0', 'time set 1000', 'weather clear', 'difficulty peaceful', 'save-all flush') {
		Send-SlipwayE2ERcon -Command $cmd | Out-Null
	}
	Start-Sleep -Seconds 3
	$info = [string](Send-SlipwayE2ERcon -Command 'slipway list')
} finally {
	Stop-SlipwayE2EServer -Server $server | Out-Null
}

# Into the play instance, as a creative world with cheats.
$saves = Join-Path $env:APPDATA "PrismLauncher\instances\$InstanceId\.minecraft\saves"
if (-not (Test-Path $saves)) { throw "Play instance not found: $saves" }
$dest = Join-Path $saves $Folder
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
Copy-Item (Join-Path (Get-SlipwayE2EServerDir) 'sandbox') $dest -Recurse
Remove-Item (Join-Path $dest 'session.lock') -Force -ErrorAction SilentlyContinue
# 26.3 keeps player data under players/; without it the player spawns at the world spawn (yaw and pitch included).
Remove-Item (Join-Path $dest 'players'), (Join-Path $dest 'playerdata'), (Join-Path $dest 'stats'), (Join-Path $dest 'advancements'), (Join-Path $dest 'singleplayer_uuid') -Recurse -Force -ErrorAction SilentlyContinue
@'
import sys, nbtlib
f = nbtlib.load(sys.argv[1])
d = f["Data"]
d["LevelName"] = nbtlib.String(sys.argv[2])
d["GameType"] = nbtlib.Int(1)
d["allowCommands"] = nbtlib.Byte(1)
d["hardcore"] = nbtlib.Byte(0)
d.pop("Player", None)
f.save()
print("level.dat:", d["LevelName"], "GameType", int(d["GameType"]), "allowCommands", int(d["allowCommands"]))
'@ | python - (Join-Path $dest 'level.dat') $LevelName
[pscustomobject]@{ World = $dest; ShipsY = $y; Vessels = $info; DemoVessel = $assembled; PlainCopyHelm = "8 $y 0"; Spawn = "0 $y -12" }
