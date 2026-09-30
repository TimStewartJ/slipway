# Scenario deck-walk: a player walks on a vessel's deck while it turns and flies, and while it is banked 45 degrees;
# they are carried along and never fall through. On a deck banked steeper than walkable (70 degrees) they slide off
# instead of clipping through. The vessel is flown by /slipway control (no pilot) so the player is free to walk.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '..\..\scenarios\_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'deck-walk'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function Watch-Rider([long]$Id, [int]$Samples, [int]$IntervalMs = 200) {
	$list = [System.Collections.Generic.List[object]]::new()
	for ($i = 0; $i -lt $Samples; $i++) {
		$r = Get-SlipwayE2ERider -Client $c
		$list.Add($r)
		Start-Sleep -Milliseconds $IntervalMs
	}
	,$list
}
function Get-Summary($List, [long]$Id) {
	$on = @($List | Where-Object { $_.Carrier -eq $Id -and $_.Local })
	$ys = @($on | ForEach-Object { $_.Local[1] })
	$minY = if ($ys.Count) { ($ys | Measure-Object -Minimum).Minimum } else { [double]::NaN }
	$maxY = if ($ys.Count) { ($ys | Measure-Object -Maximum).Maximum } else { [double]::NaN }
	[pscustomobject]@{ OnDeck = $on.Count; Total = $List.Count; MinY = $minY; MaxY = $maxY
		Text = ("{0}/{1} samples on vessel {2}, local y {3:N3}..{4:N3}" -f $on.Count, $List.Count, $Id, $minY, $maxY) }
}

try {
	$hx = 0; $hy = 150; $hz = 900
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 60) ($hy - 25) ($hz - 60) ($hx + 60) ($hy + 25) ($hz + 60)
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz -HalfX 6 -HalfZ 6 -Block 'minecraft:spruce_planks'
	Set-SlipwayE2EBlocks -Specs @(
		(New-SlipwayE2EBlock 'helm' 0 0 0 'slipway:helm' ([ordered]@{ facing = 'north' })),
		(New-SlipwayE2EBlock 'mast' 0 0 5 'minecraft:oak_log' ([ordered]@{ axis = 'y' })),
		(New-SlipwayE2EBlock 'mast2' 0 1 5 'minecraft:oak_log' ([ordered]@{ axis = 'y' })),
		(New-SlipwayE2EBlock 'lamp' 5 0 5 'minecraft:glowstone')
	) -X $hx -Y $hy -Z $hz
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	# Movement checks cover walking on the vessel, not the harness's own setup teleports.
	$movementOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
	Send-SlipwayE2ERcon -Command "slipway mode $id level false" | Out-Null
	Start-Sleep -Seconds 1
	$v = Get-SlipwayE2EVessel -Id $id
	$start = ConvertTo-SlipwayE2EWorld -Vessel $v -X -3.5 -Y 0.3 -Z -3.5
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3}" -f $c.Player, $start.X, $start.Y, $start.Z) | Out-Null
	Start-Sleep -Seconds 2
	$r = Get-SlipwayE2ERider -Client $c
	Add-SlipwayE2ECheck -Run $run -Title 'the player lands on the deck' -Condition ($r.Carrier -eq $id -and $r.onGround -eq 'true') -Detail $r.Text | Out-Null

	# 1. Walk while the vessel turns and moves forward.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 3.5 1.6 3.5" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "riderTraceStart $id" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway control $id 0.5 0 0 0 1 0 140" | Out-Null
	Start-Sleep -Milliseconds 500
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.w 30' | Out-Null
	$samples = Watch-Rider -Id $id -Samples 12
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-walking-during-turn' -Directory $run.Directory
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	$samples.AddRange((Watch-Rider -Id $id -Samples 10))
	$trace = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'riderTraceStop'
	$turning = Get-SlipwayE2EVessel -Id $id
	$sum = Get-Summary $samples $id
	Add-SlipwayE2ECheck -Run $run -Title 'walking during a turn: carried, on the deck, never below it' -Condition ($sum.OnDeck -eq $sum.Total -and $sum.MinY -gt -0.05 -and $sum.MaxY -lt 1.3) -Detail "$($sum.Text); vessel speed $($turning.speed) spin $($turning.spin); trace: $trace" -Screenshot $shot | Out-Null
	Start-Sleep -Seconds 4

	# 2. Bank to about 45 degrees (roll), stand on the slope, then walk along it while the vessel turns.
	$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	for ($i = 0; $i -lt 60 -and $tilt -lt 40; $i++) {
		Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0 0.3 2" | Out-Null
		Start-Sleep -Milliseconds 200
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	}
	Start-Sleep -Seconds 2
	for ($i = 0; $i -lt 10; $i++) {
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
		if ($tilt -lt 42) { Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0 0.2 1" | Out-Null }
		elseif ($tilt -gt 48) { Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0 -0.2 1" | Out-Null }
		else { break }
		Start-Sleep -Seconds 1
	}
	$banked = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2ECheck -Run $run -Title 'the vessel holds a bank of about 45 degrees' -Condition ($banked.tilt -gt 41 -and $banked.tilt -lt 49) -Detail ("tilt {0:N1} roll {1:N1}" -f $banked.tilt, $banked.roll) | Out-Null
	# Put the player on the slope, on a line along its contour (the local horizontal axis that stays level).
	$o = ConvertTo-SlipwayE2EWorld -Vessel $banked -X 0 -Y 0 -Z 0
	$ax = ConvertTo-SlipwayE2EWorld -Vessel $banked -X 1 -Y 0 -Z 0
	$az = ConvertTo-SlipwayE2EWorld -Vessel $banked -X 0 -Y 0 -Z 1
	$contourIsZ = [Math]::Abs($az.Y - $o.Y) -lt [Math]::Abs($ax.Y - $o.Y)
	$from = if ($contourIsZ) { @(-3.5, 0.6, -4.5) } else { @(-4.5, 0.6, -3.5) }
	$to = if ($contourIsZ) { @(-3.5, 1.2, 40) } else { @(40, 1.2, -3.5) }
	$drop = ConvertTo-SlipwayE2EWorld -Vessel $banked -X $from[0] -Y $from[1] -Z $from[2]
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3}" -f $c.Player, $drop.X, $drop.Y, $drop.Z) | Out-Null
	Start-Sleep -Milliseconds 1500
	$a = Get-SlipwayE2ERider -Client $c
	Start-Sleep -Seconds 2
	$b = Get-SlipwayE2ERider -Client $c
	$still = if ($a.Local -and $b.Local) { [Math]::Sqrt([Math]::Pow($b.Local[0] - $a.Local[0], 2) + [Math]::Pow($b.Local[1] - $a.Local[1], 2) + [Math]::Pow($b.Local[2] - $a.Local[2], 2)) } else { [double]::NaN }
	Add-SlipwayE2ECheck -Run $run -Title 'the player stands on the 45 degree bank without sliding' -Condition ($a.Carrier -eq $id -and $b.Carrier -eq $id -and $b.Local[1] -gt -0.05 -and $b.Local[1] -lt 1.3 -and $still -lt 0.2) -Detail ("moved {0:N3} in 2 s; {1}" -f $still, $b.Text) | Out-Null
	# Walk along the slope while the vessel turns about its (tilted) up axis.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "riderTraceStart $id" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0.4 0 100" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument ("lookAtLocal $id {0} {1} {2}" -f $to[0], $to[1], $to[2]) | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.w 25' | Out-Null
	$samples = Watch-Rider -Id $id -Samples 8
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-banked-45' -Directory $run.Directory
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	$samples.AddRange((Watch-Rider -Id $id -Samples 12))
	$trace = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'riderTraceStop'
	$sum = Get-Summary $samples $id
	$after = Get-SlipwayE2EVessel -Id $id
	$walked = [Math]::Sqrt([Math]::Pow($b.Local[0] - $samples[$samples.Count - 1].Local[0], 2) + [Math]::Pow($b.Local[2] - $samples[$samples.Count - 1].Local[2], 2))
	$turned = [Math]::Abs((($after.yaw - $banked.yaw + 540) % 360) - 180)
	Add-SlipwayE2ECheck -Run $run -Title 'walking on a 45 degree bank while the vessel turns: carried, on the deck, never below it' -Condition ($sum.OnDeck -eq $sum.Total -and $sum.MinY -gt -0.05 -and $sum.MaxY -lt 1.3 -and $walked -gt 1.5 -and $turned -gt 10) -Detail ("{0}; walked {1:N2} blocks on the deck while it turned {2:N1} degrees; tilt now {3:N1}; trace: {4}" -f $sum.Text, $walked, $turned, $after.tilt, $trace) -Screenshot $shot | Out-Null
	Start-Sleep -Seconds 4
	# 3. Steeper than walkable: the player slides off rather than clipping through.
	$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	$samples = [System.Collections.Generic.List[object]]::new()
	for ($i = 0; $i -lt 60 -and $tilt -lt 70; $i++) {
		Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0 0.5 2" | Out-Null
		$samples.Add((Get-SlipwayE2ERider -Client $c))
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	}
	$samples.AddRange((Watch-Rider -Id $id -Samples 15))
	# Over the deck (feet at least a box half-width inside its edges) the player must never be below its surface;
	# past the edge, sliding off, they fall below the deck's plane, which is fine.
	$over = @($samples | Where-Object { $_.Carrier -eq $id -and $_.Local -and $_.Local[0] -gt -5.65 -and $_.Local[0] -lt 6.65 -and $_.Local[2] -gt -5.65 -and $_.Local[2] -lt 6.65 })
	$lowest = $over | Sort-Object { $_.Local[1] } | Select-Object -First 1
	$minY = if ($lowest) { $lowest.Local[1] } else { 0 }
	$last = $samples[$samples.Count - 1]
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '03-steep' -Directory $run.Directory
	Add-SlipwayE2ECheck -Run $run -Title 'on a 70 degree bank the player slides off instead of clipping through' -Condition ($minY -gt -0.05 -and ($last.Carrier -ne $id -or $last.Local[0] -gt 5 -or $last.Local[0] -lt -5)) -Detail ("tilt {0:N1}; {1} samples over the deck, lowest local y {2:N3} ({3}); last: {4}" -f $tilt, $over.Count, $minY, $(if ($lowest) { $lowest.Text } else { 'none' }), $last.Text) -Screenshot $shot | Out-Null
	Send-SlipwayE2ERcon -Command "slipway mode $id level true" | Out-Null
	$pos = Get-SlipwayE2EPlayerPosition -Player $c.Player
	$kicked = @(Read-SlipwayE2ELogSince -LogPath $s.LogPath -Offset $movementOffset | Where-Object { $_ -match 'Flying is not enabled|kicked|moved wrongly|moved too quickly' })
	Add-SlipwayE2ECheck -Run $run -Title 'the server neither kicked nor corrected the walking player' -Condition ($kicked.Count -eq 0) -Detail (($kicked | Select-Object -First 3) -join ' / ') | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M5'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
