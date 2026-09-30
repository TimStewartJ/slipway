# Scenario flight-rotation: a player takes the helm of a small ship and flies it through yaw, pitch and roll with the
# real client controls (scripted axes through HelmControls), including a full pitch loop through vertical and fully
# inverted flight, then turns level mode back on, lets it right itself, and disassembles it.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '..\..\scenarios\_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'flight-rotation'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function Get-Attitude([long]$Id) { $v = Get-SlipwayE2EVessel -Id $Id; "pitch={0:N1} yaw={1:N1} roll={2:N1} tilt={3:N1} y={4:N1} speed={5:N2}" -f $v.pitch, $v.yaw, $v.roll, $v.tilt, $v.Y, $v.speed }
function Invoke-Helm([string]$Axes, [int]$Ticks) {
	$answer = Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "helm $Axes $Ticks"
	if ($answer -notmatch 'piloting') { throw "helm input refused: $answer" }
	Start-Sleep -Milliseconds ([int]($Ticks * 50 + 250))
}
function Save-Shot([string]$Name, [string]$Title) {
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name $Name -Directory $run.Directory -SettleMilliseconds 300
	Add-SlipwayE2EStep -Run $run -Title "screenshot: $Title" -Detail (Get-Attitude $script:id) -Screenshot $shot
}

try {
	$hx = 0; $hy = 150; $hz = 600
	Reset-SlipwayE2EScenario -Client $c
	$specs = Get-SlipwayE2ESmallShip
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 70) ($hy - 30) ($hz - 70) ($hx + 70) ($hy + 30) ($hz + 70)
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz -HalfX 3 -HalfZ 3
	Set-SlipwayE2EBlocks -Specs $specs -X $hx -Y $hy -Z $hz
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3} facing {1} {2} {4}" -f $c.Player, ($hx + 0.5), $hy, ($hz - 1.5), ($hz + 5)) | Out-Null
	Start-Sleep -Seconds 1
	$script:id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	$id = $script:id
	Start-Sleep -Seconds 1
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 200
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	Start-Sleep -Milliseconds 500
	$state = Invoke-SlipwayE2EAgent -Client $c -Verb state
	Add-SlipwayE2ECheck -Run $run -Title 'the player takes the helm' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb look -Argument '0 10' | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null

	# Climb, then yaw about 90 degrees.
	Invoke-Helm '0 0 1 0 0 0' 30
	$before = Get-SlipwayE2EVessel -Id $id
	Invoke-Helm '0 0 0 0 1 0' 40
	Start-Sleep -Seconds 1
	$after = Get-SlipwayE2EVessel -Id $id
	$turn = [Math]::Abs((($after.yaw - $before.yaw + 540) % 360) - 180)
	Add-SlipwayE2ECheck -Run $run -Title 'yaw input turns the vessel' -Condition ($turn -gt 45 -and $after.tilt -lt 5) -Detail ("turned {0:N1} deg, {1}" -f $turn, (Get-Attitude $id)) | Out-Null
	Save-Shot '01-after-yaw' 'after a yaw turn (level)'

	# Level mode off, then pitch the nose up.
	Invoke-SlipwayE2EAgent -Client $c -Verb key -Argument 'key.keyboard.b' | Out-Null
	Start-Sleep -Milliseconds 500
	$levelOff = (Get-SlipwayE2EVessel -Id $id).level -eq 'false'
	Add-SlipwayE2ECheck -Run $run -Title 'the level key turns level mode off' -Condition $levelOff | Out-Null
	Invoke-Helm '0 0 0 1 0 0' 12
	Start-Sleep -Seconds 1
	$pitched = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2ECheck -Run $run -Title 'pitch input pitches the vessel and it holds the attitude' -Condition ([Math]::Abs($pitched.pitch) -gt 15) -Detail (Get-Attitude $id) | Out-Null
	Save-Shot '02-pitched' 'nose pitched'
	Invoke-Helm '0 0 0 -1 0 0' 12
	Start-Sleep -Seconds 1

	# Roll until fully inverted.
	$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	for ($i = 0; $i -lt 30 -and $tilt -lt 172; $i++) {
		$ticks = if ($tilt -lt 140) { 8 } else { 2 }
		Invoke-Helm '0 0 0 0 0 1' $ticks
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	}
	Start-Sleep -Seconds 2
	$inverted = Get-SlipwayE2EVessel -Id $id
	$state = Invoke-SlipwayE2EAgent -Client $c -Verb state
	Add-SlipwayE2ECheck -Run $run -Title 'roll input flies the vessel fully inverted and it stays inverted' -Condition ($inverted.tilt -gt 160) -Detail (Get-Attitude $id) | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'the pilot stays at the helm while inverted' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null
	Save-Shot '03-inverted' 'fully inverted (third person)'
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	Save-Shot '04-inverted-pilot' 'fully inverted, pilot view with HUD'
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null

	# Roll back upright, then a full pitch loop through vertical (no gimbal lock: the attitude passes 90 degrees smoothly).
	$tilt = $inverted.tilt
	for ($i = 0; $i -lt 30 -and $tilt -gt 8; $i++) {
		$ticks = if ($tilt -gt 40) { 8 } else { 2 }
		Invoke-Helm '0 0 0 0 0 1' $ticks
		$tilt = (Get-SlipwayE2EVessel -Id $id).tilt
	}
	$samples = [System.Collections.Generic.List[double]]::new()
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'helm 0 0 0 1 0 0 150' | Out-Null
	$loopShot = $false
	for ($t = 0; $t -lt 36; $t++) {
		Start-Sleep -Milliseconds 250
		$v = Get-SlipwayE2EVessel -Id $id
		$samples.Add($v.tilt)
		if (-not $loopShot -and $v.tilt -gt 80 -and $v.tilt -lt 100) {
			$loopShot = $true
			# Mid-manoeuvre: capture now (waiting for the terrain renderer would catch the loop past vertical).
			$shot = Save-SlipwayE2EScreenshot -Client $c -Name '05-vertical' -Directory $run.Directory -TerrainTimeoutSeconds 0
			Add-SlipwayE2EStep -Run $run -Title 'screenshot: nose straight up during the loop' -Detail (Get-Attitude $id) -Screenshot $shot
		}
	}
	Start-Sleep -Seconds 2
	$maxTilt = ($samples | Measure-Object -Maximum).Maximum
	$passedVertical = @($samples | Where-Object { $_ -gt 60 -and $_ -lt 120 }).Count -gt 0
	Add-SlipwayE2ECheck -Run $run -Title 'a pitch loop passes through vertical and inverted without locking' -Condition ($passedVertical -and $maxTilt -gt 150) -Detail ("tilt samples: " + (($samples | ForEach-Object { '{0:N0}' -f $_ }) -join ' ')) | Out-Null

	# Level mode back on: the vessel rights itself.
	Invoke-SlipwayE2EAgent -Client $c -Verb key -Argument 'key.keyboard.b' | Out-Null
	$deadline = (Get-Date).AddSeconds(15)
	do { Start-Sleep -Milliseconds 500; $v = Get-SlipwayE2EVessel -Id $id } while ($v.tilt -gt 3 -and (Get-Date) -lt $deadline)
	Start-Sleep -Seconds 1
	$v = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2ECheck -Run $run -Title 'level mode rights the vessel' -Condition ($v.tilt -lt 5 -and $v.level -eq 'true') -Detail (Get-Attitude $id) | Out-Null
	Save-Shot '06-levelled' 'levelled again'

	# Disassemble: leave the helm, sneak and use it.
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 6' | Out-Null
	Start-Sleep -Seconds 1
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 40' | Out-Null
	Start-Sleep -Milliseconds 400
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 200
	$logOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	$line = @(Wait-SlipwayE2ELog -LogPath $s.LogPath -Pattern "(Disassembled vessel $id \(|Vessel $id stays assembled)" -StartOffset $logOffset -TimeoutSeconds 10)[-1]
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 0' | Out-Null
	$m = [regex]::Match($line, 'at (-?\d+), (-?\d+), (-?\d+) with (\d) quarter turns')
	Add-SlipwayE2ECheck -Run $run -Title 'the levelled vessel disassembles' -Condition $m.Success -Detail $line | Out-Null
	if ($m.Success) {
		$problems = Test-SlipwayE2EShipAt -Specs $specs -X ([long]$m.Groups[1].Value) -Y ([long]$m.Groups[2].Value) -Z ([long]$m.Groups[3].Value) -QuarterTurns ([int]$m.Groups[4].Value)
		Add-SlipwayE2ECheck -Run $run -Title 'its blocks are back in the world, turned to the snapped heading' -Condition ($problems.Count -eq 0) -Detail ($problems -join '; ') | Out-Null
	}
	Start-Sleep -Milliseconds 500
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '07-disassembled' -Directory $run.Directory -SettleMilliseconds 500
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: disassembled after the flight' -Screenshot $shot
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M3'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
