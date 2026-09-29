# Scenario save-reload: a moving vessel with a filled chest is saved when the server stops; after a restart its
# record (pose, rotation, velocity) is exactly what was saved, and once the player reconnects the vessel is back,
# drawn and moving, with its blocks and chest contents intact.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'save-reload'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

try {
	$hx = 0; $hy = 150; $hz = 2800
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y ($hy + 3) -Z ($hz - 12.5) -LookAt @($hx, $hy, $hz) -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 40) ($hy - 15) ($hz - 40) ($hx + 40) ($hy + 15) ($hz + 40)
	Enter-SlipwayE2EArea -Client $c -X ($hx + 14.5) -Y ($hy + 3) -Z ($hz - 12.5) -LookAt @($hx, $hy, $hz) -SettleSeconds 1
	$specs = Get-SlipwayE2EMixedShip
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz
	Set-SlipwayE2EBlocks -Specs $specs -X $hx -Y $hy -Z $hz
	$reference = Get-SlipwayE2EBlockEntityData -Specs $specs -X $hx -Y $hy -Z $hz
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	Send-SlipwayE2ERcon -Command "slipway mode $id level false" | Out-Null
	Send-SlipwayE2ERcon -Command "slipway rotate $id 20 10 15" | Out-Null
	Start-Sleep -Seconds 1
	# Moving: thrust forward and up for ten seconds, and stop the server in the middle of it.
	Send-SlipwayE2ERcon -Command "slipway control $id 1 0 0.3 0 0.2 0 200" | Out-Null
	Start-Sleep -Milliseconds 1500
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-before-stop' -Directory $run.Directory
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: moving vessel before the server stops' -Screenshot $shot
	$before = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2EStep -Run $run -Title 'state shortly before stopping' -Detail $before.Text

	# Stop the server with the player online (it saves on the way down and logs each vessel's saved state).
	$stopOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
	Add-SlipwayE2ECheck -Run $run -Title 'the server stops cleanly' -Condition (Stop-SlipwayE2EServer -Server $s) | Out-Null
	$stopLog = @(Read-SlipwayE2ELogSince -LogPath $s.LogPath -Offset $serverOffset)
	$stopProblems = @(Get-SlipwayE2ELogProblems -LogPath $s.LogPath -Offset $serverOffset)
	$savedLine = @(Read-SlipwayE2ELogSince -LogPath $s.LogPath -Offset $stopOffset | Where-Object { $_ -match "Vessel $id saved at" })[-1]
	$sm = [regex]::Match([string]$savedLine, 'pos=(\S+) q=(\S+) vel=(\S+)')
	if (-not $sm.Success) { throw "No saved-state line for vessel $id in the server log" }
	$savedPos = @($sm.Groups[1].Value -split ',' | ForEach-Object { [double]$_ })
	$savedQ = @($sm.Groups[2].Value -split ',' | ForEach-Object { [double]$_ })
	$savedVel = @($sm.Groups[3].Value -split ',' | ForEach-Object { [double]$_ })

	# Restart and read the record before anyone is near (the vessel is not active, so nothing has moved it).
	$s2 = Start-SlipwayE2EServer -World $s.World
	$session.Server = $s2
	$session | ConvertTo-Json -Depth 4 | Set-Content (Get-SlipwayE2ESessionFile)
	$serverOffset2 = Get-SlipwayE2ELogOffset -LogPath $s2.LogPath
	$loaded = Get-SlipwayE2EVessel -Id $id
	$dp = [Math]::Sqrt([Math]::Pow($loaded.X - $savedPos[0], 2) + [Math]::Pow($loaded.Y - $savedPos[1], 2) + [Math]::Pow($loaded.Z - $savedPos[2], 2))
	$dq = [Math]::Abs($loaded.Q[0] * $savedQ[0] + $loaded.Q[1] * $savedQ[1] + $loaded.Q[2] * $savedQ[2] + $loaded.Q[3] * $savedQ[3])
	$dv = [Math]::Sqrt([Math]::Pow($loaded.Velocity[0] - $savedVel[0], 2) + [Math]::Pow($loaded.Velocity[1] - $savedVel[1], 2) + [Math]::Pow($loaded.Velocity[2] - $savedVel[2], 2))
	$speed = [Math]::Sqrt([Math]::Pow($savedVel[0], 2) + [Math]::Pow($savedVel[1], 2) + [Math]::Pow($savedVel[2], 2))
	Add-SlipwayE2ECheck -Run $run -Title 'after a restart the vessel has exactly the saved pose, rotation and velocity' -Condition ($loaded.active -eq 'false' -and $dp -lt 0.002 -and $dq -gt 0.999999 -and $dv -lt 0.002 -and $speed -gt 1.0) -Detail ("saved: {0}; loaded: {1}; position differs {2:N4}, |q.q'| {3:N7}, velocity differs {4:N4}, saved speed {5:N2}" -f $savedLine, $loaded.Text, $dp, $dq, $dv, $speed) | Out-Null


	# Reconnect: the vessel is back, drawn, and resumes moving.
	Invoke-SlipwayE2EAgent -Client $c -Verb connect -Argument "localhost:$($s2.Port)" | Out-Null
	$deadline = (Get-Date).AddSeconds(90)
	do { Start-Sleep -Seconds 1; $where = Invoke-SlipwayE2EAgent -Client $c -Verb session } while ($where -notmatch 'world ' -and (Get-Date) -lt $deadline)
	Start-Sleep -Seconds 5
	$clientView = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'vessels'
	$live = Get-SlipwayE2EVessel -Id $id
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-after-reload' -Directory $run.Directory -SettleMilliseconds 500
	Add-SlipwayE2ECheck -Run $run -Title 'after reconnecting the client has the vessel and the server simulates it again' -Condition ($clientView -match "#$id ready=true" -and $live.active -eq 'true' -and $live.body -eq 'true') -Detail "client: $clientView; server: $($live.Text)" -Screenshot $shot | Out-Null
	# The plot is loaded again now that a player is near the vessel.
	$pa = $loaded.PlotAnchor
	$inPlot = Test-SlipwayE2EShipAt -Specs $specs -X $pa[0] -Y $pa[1] -Z $pa[2] -ReferenceData $reference
	Add-SlipwayE2ECheck -Run $run -Title 'every block and block entity (chest items, furnace, sign text) survived the restart' -Condition ($inPlot.Count -eq 0) -Detail ($inPlot -join '; ') | Out-Null
	$problems = @($stopProblems) + @(Get-SlipwayE2ELogProblems -LogPath $s2.LogPath -Offset 0)
	Add-SlipwayE2ECheck -Run $run -Title 'no errors while saving, stopping and loading' -Condition ($problems.Count -eq 0) -Detail (($problems | Select-Object -First 5) -join ' / ') | Out-Null
	Read-SlipwayE2ELogSince -LogPath $s2.LogPath -Offset 0 | Set-Content (Join-Path $run.Directory 'server-after-restart-log.txt') -Encoding UTF8
	$stopLog | Set-Content (Join-Path $run.Directory 'server-before-stop-log.txt') -Encoding UTF8
	$serverOffset = $serverOffset2
	$session.Server = $s2
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M6'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
