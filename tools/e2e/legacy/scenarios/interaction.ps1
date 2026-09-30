# Scenario interaction: on a vessel turned about 30 degrees on two axes (pitch and roll) a player places blocks on
# the deck top and against a block side (they land at the right vessel-local positions, a log takes the local axis of
# the clicked face), breaks a block in survival (it drops where the vessel is), opens a chest, pulls a lever that
# lights a redstone lamp, opens a door, and toggles the lamp again while the vessel is flying.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '..\..\scenarios\_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'interaction'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function Test-PlotBlock($Vessel, [int]$X, [int]$Y, [int]$Z, [string]$Block) {
	Test-SlipwayE2EBlock -X ($Vessel.PlotAnchor[0] + $X) -Y ($Vessel.PlotAnchor[1] + $Y) -Z ($Vessel.PlotAnchor[2] + $Z) -Block $Block
}
function Use-Local([long]$Id, [double]$X, [double]$Y, [double]$Z, [int[]]$Expect) {
	# Aim, confirm the crosshair is on the intended vessel block (the vessel may be moving), then use.
	$want = if ($Expect) { "local {0}, {1}, {2} " -f $Expect[0], $Expect[1], $Expect[2] } else { $null }
	for ($attempt = 1; $attempt -le 5; $attempt++) {
		$look = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument ("lookAtLocal {0} {1} {2} {3}" -f $Id, $X, $Y, $Z)
		Start-Sleep -Milliseconds 120
		$hit = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'hit'
		if (-not $want -or $hit.Contains($want)) { break }
	}
	try { $use = Invoke-SlipwayE2EAgent -Client $c -Verb use } catch { $use = "use failed: $($_.Exception.Message)" }
	Start-Sleep -Milliseconds 600
	"$look | $hit | $use"
}
function Stand-At($Vessel, [double]$X, [double]$Z) {
	$p = ConvertTo-SlipwayE2EWorld -Vessel $Vessel -X $X -Y 0.3 -Z $Z
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3}" -f $c.Player, $p.X, $p.Y, $p.Z) | Out-Null
	Start-Sleep -Seconds 2
	Get-SlipwayE2ERider -Client $c
}
try {
	$hx = 0; $hy = 150; $hz = 1200
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 40) ($hy - 20) ($hz - 40) ($hx + 40) ($hy + 20) ($hz + 40)
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz -HalfX 5 -HalfZ 5
	$specs = @(
		(New-SlipwayE2EBlock 'helm' 0 0 0 'slipway:helm' ([ordered]@{ facing = 'north' })),
		(New-SlipwayE2EBlock 'chest' -3 0 3 'minecraft:chest' ([ordered]@{ facing = 'north'; type = 'single' }) '{Items:[{Slot:4b,id:"minecraft:emerald",count:12}]}'),
		(New-SlipwayE2EBlock 'lamp' 3 0 3 'minecraft:redstone_lamp' ([ordered]@{ lit = 'false' })),
		(New-SlipwayE2EBlock 'lever' 3 1 3 'minecraft:lever' ([ordered]@{ face = 'floor'; facing = 'north'; powered = 'false' })),
		(New-SlipwayE2EBlock 'door-lower' -2 0 0 'minecraft:oak_door' ([ordered]@{ facing = 'north'; half = 'lower'; hinge = 'left'; open = 'false' })),
		(New-SlipwayE2EBlock 'door-upper' -2 1 0 'minecraft:oak_door' ([ordered]@{ facing = 'north'; half = 'upper'; hinge = 'left'; open = 'false' }))
	)
	Set-SlipwayE2EBlocks -Specs $specs -X $hx -Y $hy -Z $hz
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	Send-SlipwayE2ERcon -Command "slipway mode $id level false" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway rotate $id 15 30 30" | Out-Null
	Start-Sleep -Seconds 2
	$v = Get-SlipwayE2EVessel -Id $id
	$run.Notes.Add(("Vessel {0} held at pitch {1:N1}, roll {2:N1}, yaw {3:N1} (tilt {4:N1})" -f $id, $v.pitch, $v.roll, $v.yaw, $v.tilt))
	Add-SlipwayE2ECheck -Run $run -Title 'the vessel is turned about 30 degrees on two axes' -Condition ([Math]::Abs($v.pitch) -gt 20 -and [Math]::Abs($v.roll) -gt 20 -and $v.tilt -lt 50) -Detail ("pitch {0:N1} roll {1:N1} tilt {2:N1}" -f $v.pitch, $v.roll, $v.tilt) | Out-Null

	# Stand on the tilted deck (about 41 degrees: still walkable), south-east of the build spot, so the top and east
	# faces of local (1,0,0) and the south face of (2,0,0) face the player.
	Send-SlipwayE2ERcon -Command ("item replace entity {0} hotbar.0 with minecraft:stone 16" -f $c.Player) | Out-Null
	Send-SlipwayE2ERcon -Command ("item replace entity {0} hotbar.1 with minecraft:oak_log 16" -f $c.Player) | Out-Null
	Send-SlipwayE2ERcon -Command ("item replace entity {0} hotbar.2 with minecraft:diamond_pickaxe" -f $c.Player) | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb select -Argument 0 | Out-Null
	$r = Stand-At $v 3.5 2.0
	Add-SlipwayE2ECheck -Run $run -Title 'the player stands on the tilted deck' -Condition ($r.Carrier -eq $id -and $r.Local -and $r.Local[1] -gt -0.05) -Detail $r.Text | Out-Null

	# Stone on the deck top at local (1,0,0); a second against its east face lands at (2,0,0).
	$detail = Use-Local $id 1.5 0.0 0.5 @(1, -1, 0)
	Add-SlipwayE2ECheck -Run $run -Title 'placing on the deck top lands at the vessel-local position above the clicked block' -Condition (Test-PlotBlock $v 1 0 0 'minecraft:stone') -Detail $detail | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-placed-top' -Directory $run.Directory
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: placed stone, outline on the tilted vessel' -Screenshot $shot
	$detail = Use-Local $id 2.0 0.5 0.5 @(1, 0, 0)
	Add-SlipwayE2ECheck -Run $run -Title 'placing against a side face lands next to it in vessel space' -Condition (Test-PlotBlock $v 2 0 0 'minecraft:stone') -Detail $detail | Out-Null
	# Logs take the local axis of the clicked face.
	Invoke-SlipwayE2EAgent -Client $c -Verb select -Argument 1 | Out-Null
	$detail = Use-Local $id 1.5 1.0 0.5 @(1, 0, 0)
	Add-SlipwayE2ECheck -Run $run -Title 'a log placed on a top face stands on the local Y axis' -Condition (Test-PlotBlock $v 1 1 0 'minecraft:oak_log[axis=y]') -Detail $detail | Out-Null
	$detail = Use-Local $id 2.5 0.5 1.0 @(2, 0, 0)
	Add-SlipwayE2ECheck -Run $run -Title 'a log placed on a side face lies along that face''s local axis (Z)' -Condition (Test-PlotBlock $v 2 0 1 'minecraft:oak_log[axis=z]') -Detail $detail | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-placed-logs' -Directory $run.Directory
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: stones and logs placed on the tilted vessel' -Screenshot $shot

	# Break the stone at (2,0,0) from above in survival: it drops at the vessel (and the player can pick it up).
	Send-SlipwayE2ERcon -Command "gamemode survival $($c.Player)" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb select -Argument 2 | Out-Null
	Start-Sleep -Milliseconds 500
	Send-SlipwayE2ERcon -Command "kill @e[type=item]" | Out-Null
	$before = [string](Send-SlipwayE2ERcon -Command "clear $($c.Player) minecraft:stone 0")
	for ($attempt = 1; $attempt -le 5; $attempt++) {
		Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 2.5 1.0 0.5" | Out-Null
		Start-Sleep -Milliseconds 120
		$hit = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'hit'
		if ($hit.Contains('local 2, 0, 0 ')) { break }
	}
	$broken = Invoke-SlipwayE2EAgent -Client $c -Verb destroy -Argument 200 -TimeoutSeconds 20
	$v = Get-SlipwayE2EVessel -Id $id
	$where = ConvertTo-SlipwayE2EWorld -Vessel $v -X 2.5 -Y 0.5 -Z 0.5
	$drop = [string](Send-SlipwayE2ERcon -Command ("execute positioned {0} {1} {2} if entity @e[type=item,distance=..4]" -f $where.X, $where.Y, $where.Z))
	$inPlot = [string](Send-SlipwayE2ERcon -Command ("execute if entity @e[type=item,x={0},y=-64,z={1},dx=64,dy=400,dz=64]" -f ($v.PlotAnchor[0] - 32), ($v.PlotAnchor[2] - 32)))
	Start-Sleep -Milliseconds 1500
	$after = [string](Send-SlipwayE2ERcon -Command "clear $($c.Player) minecraft:stone 0")
	$countBefore = if ($before -match '(\d+)') { [int]$matches[1] } else { 0 }
	$countAfter = if ($after -match '(\d+)') { [int]$matches[1] } else { 0 }
	Add-SlipwayE2ECheck -Run $run -Title 'breaking a vessel block in survival removes it' -Condition ((Test-PlotBlock $v 2 0 0 'minecraft:air') -and $broken -match 'destroyed') -Detail "$hit | $broken" | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'the drop appears at the vessel (not in the plot) and can be picked up' -Condition (($drop -match 'passed' -or $countAfter -gt $countBefore) -and $inPlot -notmatch 'passed') -Detail ("near the vessel: {0}; in the plot: {1}; stone in inventory {2} -> {3}" -f $drop, $inPlot, $countBefore, $countAfter) | Out-Null
	Send-SlipwayE2ERcon -Command "gamemode creative $($c.Player)" | Out-Null

	# Chest, lever + lamp and door, from a spot south of the helm.
	Invoke-SlipwayE2EAgent -Client $c -Verb select -Argument 3 | Out-Null
	$r = Stand-At $v -0.5 2.5
	$detail = Use-Local $id -2.5 0.5 3.5 @(-3, 0, 3)
	Start-Sleep -Milliseconds 500
	$screen = Invoke-SlipwayE2EAgent -Client $c -Verb screen
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '03-chest-open' -Directory $run.Directory
	Add-SlipwayE2ECheck -Run $run -Title 'the chest on the tilted vessel opens with its items' -Condition ($screen -match 'ContainerScreen') -Detail "$screen | $detail" -Screenshot $shot | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb close | Out-Null
	Start-Sleep -Milliseconds 300
	$detail = Use-Local $id 3.5 1.1 3.5 @(3, 1, 3)
	Start-Sleep -Milliseconds 500
	$lit = Test-PlotBlock $v 3 0 3 'minecraft:redstone_lamp[lit=true]'
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '04-lamp-lit' -Directory $run.Directory
	Add-SlipwayE2ECheck -Run $run -Title 'the lever lights the redstone lamp' -Condition ($lit -and (Test-PlotBlock $v 3 1 3 'minecraft:lever[powered=true]')) -Detail $detail -Screenshot $shot | Out-Null
	$detail = Use-Local $id -1.5 0.5 0.95 @(-2, 0, 0)
	Add-SlipwayE2ECheck -Run $run -Title 'the door opens' -Condition (Test-PlotBlock $v -2 0 0 'minecraft:oak_door[open=true]') -Detail $detail | Out-Null

	# Redstone while flying: switch the lamp off and on again while the vessel moves.
	Send-SlipwayE2ERcon -Command "slipway control $id 0.4 0 0 0 0 0 200" | Out-Null
	Start-Sleep -Seconds 2
	$detail = Use-Local $id 3.5 1.1 3.5 @(3, 1, 3)
	$off = Test-PlotBlock $v 3 0 3 'minecraft:redstone_lamp[lit=false]'
	$detail2 = Use-Local $id 3.5 1.1 3.5 @(3, 1, 3)
	$on = Test-PlotBlock $v 3 0 3 'minecraft:redstone_lamp[lit=true]'
	$moving = Get-SlipwayE2EVessel -Id $id
	$r = Get-SlipwayE2ERider -Client $c
	Add-SlipwayE2ECheck -Run $run -Title 'the lever switches the lamp off and on while the vessel flies' -Condition ($off -and $on -and $moving.speed -gt 0.3 -and $r.Carrier -eq $id) -Detail ("speed {0:N2}; off={1} on={2}; rider {3}; {4} || {5}" -f $moving.speed, $off, $on, $r.Text, $detail, $detail2) | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '05-flying-tilted' -Directory $run.Directory -SettleMilliseconds 300
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: the tilted vessel flying with the lamp lit (third person)' -Screenshot $shot
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Invoke-SlipwayE2EAgent -Client $c -Verb close | Out-Null; Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null } catch { }
	try { Send-SlipwayE2ERcon -Command "gamemode creative $($c.Player)" | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M5'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
