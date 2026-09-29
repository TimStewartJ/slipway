# Scenario deck-walk: a player walks on a vessel's deck while it turns and flies, and while it is banked 45 degrees;
# they are carried along and never fall through. On a deck banked steeper than walkable (70 degrees) they slide off
# instead of clipping through. The vessel is flown by /slipway control (no pilot) so the player is free to walk.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

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
	Send-SlipwayE2ERcon -Command "slipway control $id 0.5 0 0 0 1 0 140" | Out-Null
	Start-Sleep -Milliseconds 500
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.w 30' | Out-Null
	$samples = Watch-Rider -Id $id -Samples 12
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-walking-during-turn' -Directory $run.Directory
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	$samples.AddRange((Watch-Rider -Id $id -Samples 10))
	$turning = Get-SlipwayE2EVessel -Id $id
	$sum = Get-Summary $samples $id
	Add-SlipwayE2ECheck -Run $run -Title 'walking during a turn: carried, on the deck, never below it' -Condition ($sum.OnDeck -eq $sum.Total -and $sum.MinY -gt -0.05 -and $sum.MaxY -lt 1.3) -Detail "$($sum.Text); vessel speed $($turning.speed) spin $($turning.spin)" -Screenshot $shot | Out-Null
	Start-Sleep -Seconds 4

	# 2. Bank to about 45 degrees (roll) and walk across the slope.
	$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	for ($i = 0; $i -lt 40 -and $tilt -lt 43; $i++) {
		Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0 0.5 2" | Out-Null
		Start-Sleep -Milliseconds 200
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	}
	Start-Sleep -Seconds 2
	$banked = Get-SlipwayE2EVessel -Id $id
	$bankOk = Add-SlipwayE2ECheck -Run $run -Title 'the vessel holds a bank of about 45 degrees' -Condition ($banked.tilt -gt 38 -and $banked.tilt -lt 52) -Detail ("tilt {0:N1} roll {1:N1}" -f $banked.tilt, $banked.roll)
	$r = Get-SlipwayE2ERider -Client $c
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 3.5 1.6 -3.5" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.w 25' | Out-Null
	$samples = Watch-Rider -Id $id -Samples 10
	Send-SlipwayE2ERcon -Command "slipway control $id 0.4 0 0 0 0.6 0 100" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-banked-45' -Directory $run.Directory
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	$samples.AddRange((Watch-Rider -Id $id -Samples 12))
	$sum = Get-Summary $samples $id
	$after = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2ECheck -Run $run -Title 'on a 45 degree bank, while turning: stays on the deck, never below it' -Condition ($sum.OnDeck -eq $sum.Total -and $sum.MinY -gt -0.05 -and $sum.MaxY -lt 1.3) -Detail "$($sum.Text); tilt now $('{0:N1}' -f $after.tilt)" -Screenshot $shot | Out-Null
	Start-Sleep -Seconds 5

	# 3. Steeper than walkable: the player slides off rather than clipping through.
	$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	$samples = [System.Collections.Generic.List[object]]::new()
	for ($i = 0; $i -lt 60 -and $tilt -lt 70; $i++) {
		Send-SlipwayE2ERcon -Command "slipway control $id 0 0 0 0 0 0.5 2" | Out-Null
		$samples.Add((Get-SlipwayE2ERider -Client $c))
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	}
	$samples.AddRange((Watch-Rider -Id $id -Samples 15))
	$onDeck = @($samples | Where-Object { $_.Carrier -eq $id -and $_.Local })
	$minY = if ($onDeck.Count) { ($onDeck | ForEach-Object { $_.Local[1] } | Measure-Object -Minimum).Minimum } else { 0 }
	$last = $samples[$samples.Count - 1]
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '03-steep' -Directory $run.Directory
	Add-SlipwayE2ECheck -Run $run -Title 'on a 70 degree bank the player slides off instead of clipping through' -Condition ($minY -gt -0.05 -and ($last.Carrier -ne $id -or $last.Local[0] -gt 5 -or $last.Local[0] -lt -5)) -Detail ("tilt {0:N1}; lowest local y while on deck {1:N3}; last: {2}" -f $tilt, $minY, $last.Text) -Screenshot $shot | Out-Null
	Send-SlipwayE2ERcon -Command "slipway mode $id level true" | Out-Null
	$pos = Get-SlipwayE2EPlayerPosition -Player $c.Player
	$kicked = @(Read-SlipwayE2ELogSince -LogPath $s.LogPath -Offset $serverOffset | Where-Object { $_ -match 'Flying is not enabled|kicked|moved wrongly|moved too quickly' })
	Add-SlipwayE2ECheck -Run $run -Title 'the server neither kicked nor corrected the walking player' -Condition ($kicked.Count -eq 0) -Detail (($kicked | Select-Object -First 3) -join ' / ') | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M5'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
