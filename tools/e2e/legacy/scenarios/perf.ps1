# Scenario perf: what a ~1,000-block vessel costs, with Iris and the Bliss shader pack on. The player watches one
# spot over open ocean from 30 blocks away: first with nothing there (baseline), then with a 961-block barge hovering
# there, then with the barge flying a slow circle in view. Each phase records client frame rate (uncapped, average and
# 5th percentile) and the server's tick time, Slipway's main-thread exchange time and its physics step time.
param([switch]$StopAfter, [int]$PhaseSeconds = 15)
. (Join-Path $PSScriptRoot '..\..\scenarios\_common.ps1')

$session = Start-SlipwayE2ESession -Shaders $true
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'perf'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function Measure-Phase([string]$Name) {
	Invoke-SlipwayE2EAgent -Client $c -Verb perf -Argument start | Out-Null
	$samples = [System.Collections.Generic.List[object]]::new()
	$end = (Get-Date).AddSeconds($PhaseSeconds)
	while ((Get-Date) -lt $end) {
		Start-Sleep -Milliseconds 1000
		$t = [string](Send-SlipwayE2ERcon -Command 'slipway stats')
		$o = [ordered]@{}
		foreach ($m in [regex]::Matches($t, '(\w+)=([\d.]+)')) { $o[$m.Groups[1].Value] = [double]$m.Groups[2].Value }
		$samples.Add([pscustomobject]$o)
	}
	$perf = [string](Invoke-SlipwayE2EAgent -Client $c -Verb perf -Argument stop)
	$fps = @{}
	foreach ($m in [regex]::Matches($perf, '(\w+)=([\d.]+)')) { $fps[$m.Groups[1].Value] = [double]$m.Groups[2].Value }
	$avg = { param($k) ($samples | ForEach-Object { $_.$k } | Measure-Object -Average).Average }
	$max = { param($k) ($samples | ForEach-Object { $_.$k } | Measure-Object -Maximum).Maximum }
	[pscustomobject]@{
		Name = $Name; AvgFps = $fps.avgFps; P5Fps = $fps.p5Fps; MinFps = $fps.minFps; FrameSamples = $fps.samples
		Mspt = (& $avg 'mspt'); MsptMax = (& $max 'mspt'); ExchangeMs = (& $avg 'exchange'); ExchangeMaxMs = (& $max 'exchange')
		StepMs = (& $avg 'step'); StepMaxMs = (& $max 'step'); Bodies = (& $max 'liveBodies')
	}
}
function Format-Phase($p) {
	"{0}: {1:N1} fps average, {2:N0} fps 5th percentile (min {3:N0}, {4} frames); server tick {5:N2} ms average (max {6:N2}); Slipway exchange {7:N3} ms (max {8:N3}); physics step {9:N3} ms (max {10:N3}) on its own thread; {11} Jolt bodies" -f `
		$p.Name, $p.AvgFps, $p.P5Fps, $p.MinFps, $p.FrameSamples, $p.Mspt, $p.MsptMax, $p.ExchangeMs, $p.ExchangeMaxMs, $p.StepMs, $p.StepMaxMs, $p.Bodies
}

try {
	$iris = Invoke-SlipwayE2EAgent -Client $c -Verb iris -Argument status
	Add-SlipwayE2ECheck -Run $run -Title 'Iris is running the Bliss shader pack' -Condition ($iris -match 'shaderPackInUse=true') -Detail $iris | Out-Null
	Reset-SlipwayE2EScenario -Client $c
	Send-SlipwayE2ERcon -Command 'time set noon' | Out-Null
	# Over open ocean (see render-iris): nothing to clear, so Distant Horizons has nothing stale to draw.
	$bx = 1620; $by = 140; $bz = 800
	$view = @($bx, ($by - 2), $bz)
	Enter-SlipwayE2EArea -Client $c -X ($bx + 0.5) -Y ($by + 6) -Z ($bz - 29.5) -LookAt $view -SettleSeconds 8
	Invoke-SlipwayE2EAgent -Client $c -Verb fpscap -Argument 260 | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb hud -Argument hide | Out-Null
	# Let chunks, Distant Horizons LODs and shaders settle before measuring anything.
	Start-Sleep -Seconds 25
	$settings = "render distance $($session.RenderDistance) chunks, Iris + Bliss, Sodium, Distant Horizons, 1280x720 window, frame rate uncapped"
	$run.Notes.Add("Settings: $settings")

	$base = Measure-Phase 'baseline (no vessel)'
	$run.Notes.Add((Format-Phase $base))

	# A 20x3x16 barge (960 blocks of concrete) and its helm.
	Clear-SlipwayE2EVolume ($bx - 12) ($by - 4) ($bz - 10) ($bx + 12) ($by + 2) ($bz + 10)
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:blue_concrete" -f ($bx - 10), ($by - 3), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:white_concrete" -f ($bx - 10), ($by - 2), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:red_concrete" -f ($bx - 10), ($by - 1), ($bz - 8), ($bx + 9), ($bz + 7)) | Out-Null
	Send-SlipwayE2ERcon -Command ("setblock {0} {1} {2} slipway:helm[facing=north]" -f $bx, $by, $bz) | Out-Null
	$id = Invoke-SlipwayE2EAssembleCommand -X $bx -Y $by -Z $bz
	$v = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2ECheck -Run $run -Title 'a vessel of about 1,000 blocks is assembled' -Condition ([int]$v.blocks -ge 900) -Detail $v.Text | Out-Null
	Start-Sleep -Seconds 4

	$static = Measure-Phase 'vessel hovering in view'
	$run.Notes.Add((Format-Phase $static))

	# A slow circle (about 9 blocks radius) that keeps it in view.
	Send-SlipwayE2ERcon -Command "slipway control $id 0.1 0 0 0 0.3 0 $(20 * ($PhaseSeconds + 10))" | Out-Null
	Start-Sleep -Seconds 4
	$flying = Measure-Phase 'vessel flying in view'
	$run.Notes.Add((Format-Phase $flying))
	$moving = Get-SlipwayE2EVessel -Id $id
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-barge-flying-bliss' -Directory $run.Directory -SettleMilliseconds 200
	# A second baseline with the same view, warm, after removing the vessel.
	Send-SlipwayE2ERcon -Command "slipway remove $id" | Out-Null
	Start-Sleep -Seconds 5
	$base2 = Measure-Phase 'baseline again (vessel removed)'
	$run.Notes.Add((Format-Phase $base2))
	Invoke-SlipwayE2EAgent -Client $c -Verb hud -Argument show | Out-Null

	# Compare with the warm baseline (the first one can still include chunk and LOD work).
	$ref = $base2
	$drop = if ($ref.AvgFps -gt 0) { 100.0 * ($ref.AvgFps - $flying.AvgFps) / $ref.AvgFps } else { [double]::NaN }
	$added = $flying.Mspt - $ref.Mspt
	$summary = "client {0:N1} -> {1:N1} fps average ({2:N1}% lower), 5th percentile {3:N0} -> {4:N0} fps; server tick {5:N2} -> {6:N2} ms ({7:+0.00;-0.00} ms), worst sampled {8:N2} ms; Slipway main-thread exchange {9:N3} ms average; physics step {10:N3} ms average on its own thread" -f `
		$ref.AvgFps, $flying.AvgFps, $drop, $ref.P5Fps, $flying.P5Fps, $ref.Mspt, $flying.Mspt, $added, $flying.MsptMax, $flying.ExchangeMs, $flying.StepMs
	$run.Notes.Add("Summary (961-block vessel flying vs. the warm baseline without it): $summary")
	Add-SlipwayE2ECheck -Run $run -Title 'the vessel really flew during the measurement' -Condition ($moving.speed -gt 1.0 -and $moving.spin -gt 0.1) -Detail $moving.Text -Screenshot $shot | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'server tick time stays well under 50 ms with the ~1,000-block vessel flying' -Condition ($flying.MsptMax -lt 25 -and $flying.Mspt -lt 15) -Detail $summary | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'frame rate measured in every phase' -Condition ($base.FrameSamples -gt 5 -and $static.FrameSamples -gt 5 -and $flying.FrameSamples -gt 5 -and $base2.FrameSamples -gt 5) -Detail ((@($base, $static, $flying, $base2) | ForEach-Object { Format-Phase $_ }) -join ' || ') | Out-Null
	[ordered]@{ settings = $settings; phases = @($base, $static, $flying, $base2); summary = $summary } | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $run.Directory 'perf.json')
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Invoke-SlipwayE2EAgent -Client $c -Verb fpscap -Argument 120 | Out-Null; Invoke-SlipwayE2EAgent -Client $c -Verb hud -Argument show | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M7'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
