# Scenario render-iris: with Iris and the Bliss shader pack on, a vessel near the player is drawn by the shader pack
# (lit, and casting a shadow in the shadow pass), also when it is rotated; a large vessel 320 blocks away, beyond the
# vanilla render distance, is still visible through its Distant Horizons proxy, which follows the vessel when it moves.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '..\..\scenarios\_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld -Shaders $true
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'render-iris'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function Wait-LodAir([int]$X, [int]$Y, [int]$Z, [int]$Seconds = 12) {
	# Distant Horizons rebuilds LODs asynchronously; poll its data at a block until it reads air.
	$deadline = (Get-Date).AddSeconds($Seconds)
	do {
		$lod = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument ("dhBlockAt {0} {1} {2}" -f $X, $Y, $Z)
		if ($lod -match '^air') { break }
		Start-Sleep -Milliseconds 500
	} while ((Get-Date) -lt $deadline)
	$lod
}

try {	$iris = Invoke-SlipwayE2EAgent -Client $c -Verb iris -Argument status
	Add-SlipwayE2ECheck -Run $run -Title 'Iris is running the Bliss shader pack' -Condition ($iris -match 'shaderPackInUse=true') -Detail $iris | Out-Null
	Reset-SlipwayE2EScenario -Client $c
	Send-SlipwayE2ERcon -Command 'time set 2500' | Out-Null

	# Near: the mixed ship five blocks above a smooth stone pad, morning sun so the shadow falls beside it. Everything is
	# built over open ocean (x 1376..1826, z 673..1123 in the e2e world), below the cloud layer: clearing terrain with
	# /fill would leave Distant Horizons with stale LODs of what was removed.
	$hx = 1550; $hy = 126; $hz = 900
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y 132 -Z ($hz - 13.5) -LookAt @($hx, 123, $hz) -SettleSeconds 4
	Clear-SlipwayE2EVolume ($hx - 25) 120 ($hz - 25) ($hx + 25) 145 ($hz + 25)
	Send-SlipwayE2ERcon -Command ("fill {0} 120 {1} {2} 120 {3} minecraft:smooth_stone" -f ($hx - 14), ($hz - 14), ($hx + 14), ($hz + 14)) | Out-Null
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y 132 -Z ($hz - 13.5) -LookAt @($hx, 123, $hz) -SettleSeconds 1
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz
	Set-SlipwayE2EBlocks -Specs (Get-SlipwayE2EMixedShip) -X $hx -Y $hy -Z $hz
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	Start-Sleep -Seconds 4
	# Distant Horizons captured the ship's blocks while they stood in the world; after assembly its LOD there must be
	# rebuilt (else a "ghost" of the ship stays where it was built, and hides the real one until it moves away).
	$lod = Wait-LodAir ($hx + 2) ($hy - 1) ($hz + 2)
	Add-SlipwayE2ECheck -Run $run -Title 'after assembly Distant Horizons no longer has the ship at its build site' -Condition ($lod -match '^air') -Detail "LOD at the old deck block: $lod" | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-near-shaders' -Directory $run.Directory -HideHud -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: vessel near, Bliss shaders, morning sun (look for its shadow on the pad)' -Screenshot $shot
	# The hovered block's outline on a vessel with shaders on, standing and then at the helm: outlines are main-pass
	# only (VesselRenderer skips them in Iris's shadow pass, where Iris has no program for outline lines; the soak's
	# setup reproduced that error, this step checks the outline draws without errors). Not with the HUD hidden: that
	# hides outlines too.
	Send-SlipwayE2ERcon -Command 'time set noon' | Out-Null
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 1
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Seconds 2
	$hit = [string](Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'hit')
	Add-SlipwayE2ECheck -Run $run -Title 'the crosshair is on a vessel block with shaders on (its outline is drawn)' -Condition ($hit -match "^vessel $id ") -Detail $hit | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01b-outline-shaders' -Directory $run.Directory -SettleMilliseconds 300
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: block outline on the vessel with shaders on' -Screenshot $shot
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	Start-Sleep -Seconds 2
	$state = [string](Invoke-SlipwayE2EAgent -Client $c -Verb state)
	Add-SlipwayE2ECheck -Run $run -Title 'the player takes the helm with shaders on' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 6' | Out-Null
	Start-Sleep -Seconds 1
	Send-SlipwayE2ERcon -Command 'time set 2500' | Out-Null
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y 132 -Z ($hz - 13.5) -LookAt @($hx, 123, $hz) -SettleSeconds 1
	Send-SlipwayE2ERcon -Command "slipway mode $id level false" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway rotate $id 30 20 25" | Out-Null
	Start-Sleep -Seconds 3
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-near-rotated-shaders' -Directory $run.Directory -HideHud -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: the same vessel rotated on three axes, shaders on' -Screenshot $shot
	$fps = Invoke-SlipwayE2EAgent -Client $c -Verb fps
	$run.Notes.Add("FPS while looking at the near vessel with Bliss: $fps")

	# Far: a 20x3x16 barge (about 960 blocks) 320 blocks from the player.
	$bx = 1700; $by = 140; $bz = 1000
	Enter-SlipwayE2EArea -Client $c -X ($bx + 0.5) -Y ($by + 4) -Z ($bz - 20.5) -LookAt @($bx, $by, $bz) -SettleSeconds 4
	Clear-SlipwayE2EVolume ($bx - 30) ($by - 6) ($bz - 30) ($bx + 30) ($by + 10) ($bz + 30)
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:blue_concrete" -f ($bx - 10), ($by - 3), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:white_concrete" -f ($bx - 10), ($by - 2), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:red_concrete" -f ($bx - 10), ($by - 1), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("setblock {0} {1} {2} slipway:helm[facing=north]" -f $bx, $by, $bz) | Out-Null
	$barge = Invoke-SlipwayE2EAssembleCommand -X $bx -Y $by -Z $bz
	# Clearing the volume took the glass platform too: put the player back on one.
	Enter-SlipwayE2EArea -Client $c -X ($bx + 0.5) -Y ($by + 4) -Z ($bz - 20.5) -LookAt @($bx, $by, $bz) -SettleSeconds 1
	Start-Sleep -Seconds 2
	$lod = Wait-LodAir ($bx + 3) ($by - 1) ($bz + 3)
	Add-SlipwayE2ECheck -Run $run -Title 'after assembly Distant Horizons no longer has the barge at its build site' -Condition ($lod -match '^air') -Detail "LOD at the old red concrete layer: $lod" | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '03-barge-near' -Directory $run.Directory -HideHud -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: the barge up close (real mesh)' -Screenshot $shot
	# Far away the barge's area unloads and the vessel stops (it is simulated only while its chunk is loaded); keep its
	# path loaded, as another player near it would, so it can still move while this player watches from afar.
	Send-SlipwayE2ERcon -Command ("forceload add {0} {1} {2} {3}" -f ($bx - 40), ($bz - 24), ($bx + 110), ($bz + 24)) | Out-Null
	$farZ = $bz - 320
	Enter-SlipwayE2EArea -Client $c -X ($bx + 0.5) -Y ($by + 12) -Z $farZ -LookAt @($bx, $by, $bz) -SettleSeconds 10
	$dh = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'dh'
	$vessels = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'vessels'
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '04-barge-320-blocks-dh' -Directory $run.Directory -HideHud -SettleMilliseconds 1000
	$om = [regex]::Match($dh, "proxy#$barge@(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)(\(inactive\))?")
	Add-SlipwayE2ECheck -Run $run -Title 'the far barge is drawn by an active Distant Horizons proxy at its pose' -Condition ($om.Success -and -not $om.Groups[4].Success -and [Math]::Abs([double]$om.Groups[1].Value - $bx) -lt 0.5 -and [Math]::Abs([double]$om.Groups[3].Value - $bz) -lt 0.5) -Detail "dh: $dh; client vessels: $vessels" -Screenshot $shot | Out-Null
	$moved = [string](Send-SlipwayE2ERcon -Command ("slipway pose {0} {1} {2} {3} 0 0 0" -f $barge, ($bx + 60), ($by + 10), $bz))
	Start-Sleep -Seconds 3
	$dh2 = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'dh'
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '05-barge-moved-dh' -Directory $run.Directory -HideHud -SettleMilliseconds 1000
	$om2 = [regex]::Match($dh2, "proxy#$barge@(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)(\(inactive\))?")
	Add-SlipwayE2ECheck -Run $run -Title 'when the barge moves 60 blocks east and 10 up, its proxy follows' -Condition ($om2.Success -and -not $om2.Groups[4].Success -and [Math]::Abs([double]$om2.Groups[1].Value - ($bx + 60)) -lt 0.5 -and [Math]::Abs([double]$om2.Groups[2].Value - ($by + 10)) -lt 0.5) -Detail "pose command: $moved; dh: $dh2" -Screenshot $shot | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Send-SlipwayE2ERcon -Command 'time set noon' | Out-Null; Send-SlipwayE2ERcon -Command 'forceload remove all' | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M4'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
