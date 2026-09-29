# Scenario render-iris: with Iris and the Bliss shader pack on, a vessel near the player is drawn by the shader pack
# (lit, and casting a shadow in the shadow pass), also when it is rotated; a large vessel 320 blocks away, beyond the
# vanilla render distance, is still visible through its Distant Horizons proxy, which follows the vessel when it moves.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld -Shaders $true
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'render-iris'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

try {
	$iris = Invoke-SlipwayE2EAgent -Client $c -Verb iris -Argument status
	Add-SlipwayE2ECheck -Run $run -Title 'Iris is running the Bliss shader pack' -Condition ($iris -match 'shaderPackInUse=true') -Detail $iris | Out-Null
	Reset-SlipwayE2EScenario -Client $c
	Send-SlipwayE2ERcon -Command 'time set 2500' | Out-Null

	# Near: the mixed ship five blocks above a smooth stone pad, morning sun so the shadow falls beside it.
	$hx = 0; $hy = 106; $hz = 2100
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y 112 -Z ($hz - 13.5) -LookAt @($hx, 103, $hz) -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 25) 101 ($hz - 25) ($hx + 25) 130 ($hz + 25)
	Send-SlipwayE2ERcon -Command ("fill {0} 100 {1} {2} 100 {3} minecraft:smooth_stone" -f ($hx - 14), ($hz - 14), ($hx + 14), ($hz + 14)) | Out-Null
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y 112 -Z ($hz - 13.5) -LookAt @($hx, 103, $hz) -SettleSeconds 1
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz
	Set-SlipwayE2EBlocks -Specs (Get-SlipwayE2EMixedShip) -X $hx -Y $hy -Z $hz
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	Start-Sleep -Seconds 4
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-near-shaders' -Directory $run.Directory -HideHud -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: vessel near, Bliss shaders, morning sun (look for its shadow on the pad)' -Screenshot $shot
	Send-SlipwayE2ERcon -Command "slipway mode $id level false" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway rotate $id 30 20 25" | Out-Null
	Start-Sleep -Seconds 3
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-near-rotated-shaders' -Directory $run.Directory -HideHud -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: the same vessel rotated on three axes, shaders on' -Screenshot $shot
	$fps = Invoke-SlipwayE2EAgent -Client $c -Verb fps
	$run.Notes.Add("FPS while looking at the near vessel with Bliss: $fps")

	# Far: a 20x3x16 barge (about 960 blocks) 320 blocks from the player.
	$bx = 0; $by = 140; $bz = 2500
	Enter-SlipwayE2EArea -Client $c -X ($bx + 0.5) -Y ($by + 4) -Z ($bz - 20.5) -LookAt @($bx, $by, $bz) -SettleSeconds 4
	Clear-SlipwayE2EVolume ($bx - 30) ($by - 6) ($bz - 30) ($bx + 30) ($by + 10) ($bz + 30)
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:blue_concrete" -f ($bx - 10), ($by - 3), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:white_concrete" -f ($bx - 10), ($by - 2), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:red_concrete" -f ($bx - 10), ($by - 1), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("setblock {0} {1} {2} slipway:helm[facing=north]" -f $bx, $by, $bz) | Out-Null
	$barge = Invoke-SlipwayE2EAssembleCommand -X $bx -Y $by -Z $bz
	Start-Sleep -Seconds 3
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '03-barge-near' -Directory $run.Directory -HideHud -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: the barge up close (real mesh)' -Screenshot $shot
	$farZ = $bz - 320
	Enter-SlipwayE2EArea -Client $c -X ($bx + 0.5) -Y ($by + 12) -Z $farZ -LookAt @($bx, $by, $bz) -SettleSeconds 10
	$dh = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'dh'
	$vessels = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'vessels'
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '04-barge-320-blocks-dh' -Directory $run.Directory -HideHud -SettleMilliseconds 1000
	Add-SlipwayE2ECheck -Run $run -Title 'the far barge has a Distant Horizons proxy' -Condition ($dh -match 'groups=([1-9]\d*) boxes=([1-9]\d*)') -Detail "dh: $dh; client vessels: $vessels" -Screenshot $shot | Out-Null
	Send-SlipwayE2ERcon -Command ("slipway pose {0} {1} {2} {3} 0 0 0" -f $barge, ($bx + 60), ($by + 10), $bz) | Out-Null
	Start-Sleep -Seconds 3
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '05-barge-moved-dh' -Directory $run.Directory -HideHud -SettleMilliseconds 1000
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: after the barge moved 60 blocks east and 10 up (the proxy follows)' -Screenshot $shot
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Send-SlipwayE2ERcon -Command 'time set noon' | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M4'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
