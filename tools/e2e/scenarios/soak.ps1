# Scenario soak: a player pilots the mixed ship (chest, furnace, door, sign, lamp and lever aboard) for a long session
# with Iris and Bliss on, through a repeating one-minute program: a fast run, turns, a climb, pitch up and down, a full
# roll through inverted, strafing, a hover-off drop and a return leg. Every 30 seconds it samples the server's tick
# time and memory, Slipway's exchange and physics step times, the client's frame rate and the vessel's state. It passes
# when neither process crashed, no errors were logged, every sample was finite and in bounds, and the ship is intact.
param([int]$Minutes = 20, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

$session = Start-SlipwayE2ESession -Shaders $true
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'soak'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

# The one-minute program: seconds, helm axes (forward strafe vertical pitch yaw roll), and a mode change before it.
$program = @(
	@{ S = 8; Axes = '1 0 0 0 0 0' },
	@{ S = 6; Axes = '0.5 0 0 0 1 0' },
	@{ S = 3; Axes = '0 0 1 0.5 0 0' },
	@{ S = 4; Axes = '0.5 0 0 -0.5 0 0' },
	@{ S = 3; Axes = '0 0 -1 0 0 0' },
	@{ S = 3; Axes = '0 0 0 0 0 0'; Mode = 'level true' },
	@{ S = 4; Axes = '0 0 0 0 0 1'; Mode = 'level false' },
	@{ S = 4; Axes = '0 0 0 0 0 1' },
	@{ S = 5; Axes = '0 0 0 0 0 0'; Mode = 'level true' },
	@{ S = 3; Axes = '0 1 0 0 0 0' },
	@{ S = 3; Axes = '0 -1 0 0 0 0' },
	@{ S = 2; Axes = '0 0 0 0 0 0'; Mode = 'hover false' },
	@{ S = 3; Axes = '0 0 0 0 0 0'; Mode = 'hover true' },
	@{ S = 8; Axes = '-1 0 0 0 0 0' },
	@{ S = 1; Axes = '0 0 0 0 0 0' }
)

function Get-Sample([long]$Id, [double]$Elapsed) {
	$stats = [string](Send-SlipwayE2ERcon -Command 'slipway stats')
	$o = [ordered]@{ Minute = [Math]::Round($Elapsed / 60.0, 2) }
	foreach ($m in [regex]::Matches($stats, '(\w+)=([\d.]+)')) { $o[$m.Groups[1].Value] = [double]$m.Groups[2].Value }
	$v = Get-SlipwayE2EVessel -Id $Id
	$o.Blocks = [int]$v.blocks; $o.Speed = $v.speed; $o.Tilt = $v.tilt; $o.Y = $v.Y; $o.Finite = [bool]($v.Text -notmatch 'NaN|Infinity')
	$o.Fps = [int](Invoke-SlipwayE2EAgent -Client $c -Verb fps)
	$o.ServerPrivateMb = [Math]::Round((Get-Process -Id $s.ProcessId).PrivateMemorySize64 / 1MB, 0)
	$o.ClientPrivateMb = [Math]::Round((Get-Process -Id $c.ProcessId).PrivateMemorySize64 / 1MB, 0)
	$o.Piloting = [string](Invoke-SlipwayE2EAgent -Client $c -Verb state) -match 'vehicle=vessel'
	[pscustomobject]$o
}

try {
	Reset-SlipwayE2EScenario -Client $c
	Invoke-SlipwayE2EAgent -Client $c -Verb fpscap -Argument 120 | Out-Null
	Send-SlipwayE2ERcon -Command 'time set noon' | Out-Null
	$hx = 1600; $hy = 180; $hz = 900
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 5
	Clear-SlipwayE2EVolume ($hx - 12) ($hy - 4) ($hz - 12) ($hx + 12) ($hy + 6) ($hz + 12)
	$specs = Get-SlipwayE2EMixedShip
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz
	Set-SlipwayE2EBlocks -Specs $specs -X $hx -Y $hy -Z $hz
	$reference = Get-SlipwayE2EBlockEntityData -Specs $specs -X $hx -Y $hy -Z $hz
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3} facing {1} {2} {4}" -f $c.Player, ($hx + 0.5), $hy, ($hz - 1.5), ($hz + 5)) | Out-Null
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	Start-Sleep -Seconds 2
	$blocks0 = [int](Get-SlipwayE2EVessel -Id $id).blocks
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 300
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	Start-Sleep -Milliseconds 500
	$state = Invoke-SlipwayE2EAgent -Client $c -Verb state
	Add-SlipwayE2ECheck -Run $run -Title 'the player takes the helm' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null

	$samples = [System.Collections.Generic.List[object]]::new()
	$clock = [System.Diagnostics.Stopwatch]::StartNew()
	$nextSample = 0.0
	$nextShot = 60.0
	$crashed = $null
	$startY = (Get-SlipwayE2EVessel -Id $id).Y
	while ($clock.Elapsed.TotalMinutes -lt $Minutes -and -not $crashed) {
		# Altitude hold between rounds: the program's drops and climbs do not cancel exactly.
		$dy = $startY - (Get-SlipwayE2EVessel -Id $id).Y
		$cycle = [System.Collections.Generic.List[object]]::new()
		if ([Math]::Abs($dy) -gt 6) { $cycle.Add(@{ S = [Math]::Min(8, [Math]::Max(1, [int][Math]::Round([Math]::Abs($dy) / 12))); Axes = $(if ($dy -gt 0) { '0 0 1 0 0 0' } else { '0 0 -1 0 0 0' }) }) }
		$cycle.AddRange([object[]]$program)
		foreach ($step in $cycle) {
			if ($step.Mode) { Send-SlipwayE2ERcon -Command "slipway mode $id $($step.Mode)" | Out-Null }
			Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument ("helm {0} {1}" -f $step.Axes, ($step.S * 20)) | Out-Null
			$stepEnd = $clock.Elapsed.TotalSeconds + $step.S
			while ($clock.Elapsed.TotalSeconds -lt $stepEnd) {
				if ($clock.Elapsed.TotalSeconds -ge $nextSample) {
					$samples.Add((Get-Sample $id $clock.Elapsed.TotalSeconds))
					$nextSample += 30
				}
				if ($clock.Elapsed.TotalSeconds -ge $nextShot) {
					Save-SlipwayE2EScreenshot -Client $c -Name ("soak-{0:D2}min" -f [int]$clock.Elapsed.TotalMinutes) -Directory $run.Directory | Out-Null
					$nextShot += 300
				}
				Start-Sleep -Milliseconds 250
			}
			if (-not (Get-Process -Id $s.ProcessId -ErrorAction SilentlyContinue)) { $crashed = 'server'; break }
			if (-not (Get-Process -Id $c.ProcessId -ErrorAction SilentlyContinue)) { $crashed = 'client'; break }
		}
	}
	$samples | ConvertTo-Json | Set-Content (Join-Path $run.Directory 'samples.json')
	Add-SlipwayE2ECheck -Run $run -Title "neither the server nor the client crashed in $Minutes minutes" -Condition (-not $crashed -and $clock.Elapsed.TotalMinutes -ge $Minutes) -Detail ("ran {0:N1} minutes; {1} samples; crashed: {2}" -f $clock.Elapsed.TotalMinutes, $samples.Count, $(if ($crashed) { $crashed } else { 'no' })) | Out-Null

	# Land the ship on level, idle input, and check it.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'helm 0 0 0 0 0 0 100' | Out-Null
	Send-SlipwayE2ERcon -Command "slipway mode $id level true" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway mode $id hover true" | Out-Null
	Start-Sleep -Seconds 6
	$final = Get-SlipwayE2EVessel -Id $id
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name 'soak-end' -Directory $run.Directory
	$bad = @($samples | Where-Object { -not $_.Finite -or -not $_.Piloting -or $_.Blocks -ne $blocks0 })
	$msptMax = ($samples | Measure-Object -Property mspt -Maximum).Maximum
	$msptAvg = ($samples | Measure-Object -Property mspt -Average).Average
	$stepMax = ($samples | Measure-Object -Property step -Maximum).Maximum
	$fps = $samples | Measure-Object -Property Fps -Average -Minimum
	$first = $samples | Select-Object -Skip 2 -First 1
	$last = $samples[$samples.Count - 1]
	Add-SlipwayE2ECheck -Run $run -Title 'every sample is finite, the pilot stayed at the helm and no block was lost' -Condition ($bad.Count -eq 0 -and $final.Text -notmatch 'NaN|Infinity') -Detail ("{0} bad samples of {1}; final: {2}" -f $bad.Count, $samples.Count, $final.Text) -Screenshot $shot | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'the server kept its tick time well under 50 ms' -Condition ($msptMax -lt 25) -Detail ("server tick {0:N2} ms average, worst sampled {1:N2} ms; physics step worst {2:N3} ms; client {3:N0} fps average, {4:N0} lowest sample" -f $msptAvg, $msptMax, $stepMax, $fps.Average, $fps.Minimum) | Out-Null
	$run.Notes.Add(("memory from minute {0} to {1}: server private {2} -> {3} MB, client private {4} -> {5} MB; live Jolt bodies {6} -> {7}" -f $first.Minute, $last.Minute, $first.ServerPrivateMb, $last.ServerPrivateMb, $first.ClientPrivateMb, $last.ClientPrivateMb, $first.liveBodies, $last.liveBodies))
	$pa = $final.PlotAnchor
	$intact = Test-SlipwayE2EShipAt -Specs $specs -X $pa[0] -Y $pa[1] -Z $pa[2] -ReferenceData $reference
	Add-SlipwayE2ECheck -Run $run -Title 'after the soak every block and block entity of the ship is intact' -Condition ($intact.Count -eq 0) -Detail ($intact -join '; ') | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M7'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
