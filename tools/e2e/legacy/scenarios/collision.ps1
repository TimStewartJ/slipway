# Scenario collision: (1) a vessel with hover off falls onto a stone landing pad and comes to rest on top of it
# without sinking in; (2) one hovering vessel is driven into another: they collide, never overlap, and the struck one
# is pushed away.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '..\..\scenarios\_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'collision'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function New-Box([long]$X, [long]$Y, [long]$Z, [string]$Block) {
	# 5x5 deck, a 5x5 ring of walls two high, helm in the middle: a sturdy crate-like vessel.
	New-SlipwayE2EDeck -X $X -Y $Y -Z $Z -HalfX 2 -HalfZ 2 -Block $Block
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {4} {5} {6} hollow" -f ($X - 2), $Y, ($Z - 2), ($X + 2), ($Y + 1), ($Z + 2), $Block) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {4} {5} minecraft:air" -f ($X - 1), ($Y + 1), ($Z - 1), ($X + 1), ($Y + 1), ($Z + 1)) | Out-Null
	Send-SlipwayE2ERcon -Command ("setblock {0} {1} {2} slipway:helm[facing=north]" -f $X, $Y, $Z) | Out-Null
	Invoke-SlipwayE2EAssembleCommand -X $X -Y $Y -Z $Z
}

try {
	$hx = 0; $hz = 1500
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx - 14.5) -Y 118 -Z ($hz - 14.5) -LookAt @($hx, 108, $hz) -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 30) 101 ($hz - 30) ($hx + 30) 160 ($hz + 50)
	Send-SlipwayE2ERcon -Command ("fill {0} 100 {1} {2} 100 {3} minecraft:smooth_stone" -f ($hx - 8), ($hz - 8), ($hx + 8), ($hz + 8)) | Out-Null
	Enter-SlipwayE2EArea -Client $c -X ($hx - 14.5) -Y 118 -Z ($hz - 14.5) -LookAt @($hx, 108, $hz) -SettleSeconds 1

	# 1. Terrain: drop from 14 blocks up.
	$a = New-Box -X $hx -Y 115 -Z $hz -Block 'minecraft:cobblestone'
	Start-Sleep -Seconds 1
	Send-SlipwayE2ERcon -Command "slipway mode $a hover false" | Out-Null
	$ys = [System.Collections.Generic.List[double]]::new()
	for ($i = 0; $i -lt 30; $i++) { Start-Sleep -Milliseconds 200; $ys.Add((Get-SlipwayE2EVessel -Id $a).Y) }
	Start-Sleep -Seconds 2
	$rest = Get-SlipwayE2EVessel -Id $a
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-landed' -Directory $run.Directory -SettleMilliseconds 300
	# The deck is local y -1 (bottom at pos.y - 1), the pad top is y 101: at rest pos.y should be about 102.
	$minY = ($ys | Measure-Object -Minimum).Minimum
	Add-SlipwayE2ECheck -Run $run -Title 'a falling vessel lands on the terrain and rests on top of it' -Condition ([Math]::Abs($rest.Y - 102) -lt 0.25 -and $rest.speed -lt 0.3 -and $minY -gt 101.7 -and $rest.tilt -lt 5) -Detail ("rest pos.y {0:N3} (expected 102), lowest {1:N3}, speed {2:N3}, tilt {3:N1}; fall samples {4}" -f $rest.Y, $minY, $rest.speed, $rest.tilt, (($ys | ForEach-Object { '{0:N1}' -f $_ }) -join ' ')) -Screenshot $shot | Out-Null
	Send-SlipwayE2ERcon -Command "slipway mode $a hover true" | Out-Null

	# 2. Vessel against vessel: B hovers 12 blocks south of A at the same height; A thrusts forward (south) into it.
	Enter-SlipwayE2EArea -Client $c -X ($hx + 16.5) -Y 134 -Z ($hz + 20.5) -LookAt @($hx, 130, ($hz + 26)) -SettleSeconds 1
	$b = New-Box -X $hx -Y 130 -Z ($hz + 20) -Block 'minecraft:oak_planks'
	$d = New-Box -X $hx -Y 130 -Z ($hz + 32) -Block 'minecraft:white_wool'
	Start-Sleep -Seconds 2
	$b0 = Get-SlipwayE2EVessel -Id $b; $d0 = Get-SlipwayE2EVessel -Id $d
	Send-SlipwayE2ERcon -Command "slipway control $b 1 0 0 0 0 0 80" | Out-Null
	$gaps = [System.Collections.Generic.List[double]]::new()
	$shotTaken = $false
	for ($i = 0; $i -lt 40; $i++) {
		Start-Sleep -Milliseconds 150
		$vb = Get-SlipwayE2EVessel -Id $b; $vd = Get-SlipwayE2EVessel -Id $d
		# Both boxes are 5 long in z (local -2..2 plus one), centred on their helms: they overlap when the centres are closer than 5.
		$gaps.Add([Math]::Sqrt([Math]::Pow($vd.CX - $vb.CX, 2) + [Math]::Pow($vd.CZ - $vb.CZ, 2) + [Math]::Pow($vd.CY - $vb.CY, 2)))
		if (-not $shotTaken -and $gaps[$gaps.Count - 1] -lt 5.6) {
			$shotTaken = $true
			$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-vessels-in-contact' -Directory $run.Directory
		}
	}
	Start-Sleep -Seconds 2
	$d1 = Get-SlipwayE2EVessel -Id $d
	if (-not $shotTaken) { $shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-vessels-after' -Directory $run.Directory }
	$minGap = ($gaps | Measure-Object -Minimum).Minimum
	$pushed = [Math]::Sqrt([Math]::Pow($d1.CX - $d0.CX, 2) + [Math]::Pow($d1.CZ - $d0.CZ, 2))
	Add-SlipwayE2ECheck -Run $run -Title 'two vessels collide without overlapping' -Condition ($minGap -gt 4.7 -and $minGap -lt 6.5) -Detail ("closest centre distance {0:N3} (boxes are 5 long, so contact is at 5.0); samples {1}" -f $minGap, (($gaps | ForEach-Object { '{0:N2}' -f $_ }) -join ' ')) -Screenshot $shot | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'the struck vessel is pushed away' -Condition ($pushed -gt 0.5) -Detail ("struck vessel moved {0:N2} blocks" -f $pushed) | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M3'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
