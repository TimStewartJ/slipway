# Scenario assemble-mixed: a player assembles a mixed-block ship with the helm (chest with items, furnace with items,
# door, sign with text, redstone lamp + lever, stairs, torch), everything is in the plot with identical states and
# block-entity data, the player pilots it to a new place and heading, and disassembles it with sneak + use; every
# block comes back with its state turned by the snapped heading and identical block-entity data.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'assemble-mixed'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog
try {
	$hx = 300; $hy = 110; $hz = 0
	$specs = Get-SlipwayE2EMixedShip
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 3
	# Leftovers of earlier runs (the ship flies about 35 blocks) must not block this run.
	Clear-SlipwayE2EVolume ($hx - 60) ($hy - 4) ($hz - 60) ($hx + 60) ($hy + 24) ($hz + 60)
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz
	Set-SlipwayE2EBlocks -Specs $specs -X $hx -Y $hy -Z $hz
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3} facing {4} {5} {6}" -f $c.Player, ($hx + 0.5), $hy, ($hz - 1.5), ($hx + 0.5), ($hy + 0.5), ($hz + 3)) | Out-Null
	Start-Sleep -Seconds 1
	$reference = Get-SlipwayE2EBlockEntityData -Specs $specs -X $hx -Y $hy -Z $hz
	$run.Notes.Add("Ship: 9x9 oak deck + $($specs.Count) blocks around the helm at $hx $hy $hz; block entities: $($reference.Keys -join ', ')")
	$before = Test-SlipwayE2EShipAt -Specs $specs -X $hx -Y $hy -Z $hz -ReferenceData $reference
	Add-SlipwayE2ECheck -Run $run -Title 'ship built in the world' -Condition ($before.Count -eq 0 -and $reference.Count -eq 3) -Detail ($before -join '; ') | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '01-built' -Directory $run.Directory -SettleMilliseconds 800
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: ship before assembly' -Screenshot $shot
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null

	# Assemble like a player: look at the helm and use it.
	$logOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
	Invoke-SlipwayE2EAgent -Client $c -Verb lookat -Argument ("{0} {1} {2}" -f ($hx + 0.5), ($hy + 0.5), ($hz + 0.5)) | Out-Null
	Start-Sleep -Milliseconds 300
	$use = Invoke-SlipwayE2EAgent -Client $c -Verb use
	$id = Get-SlipwayE2ENewVesselId -Server $s -Offset $logOffset
	$vessel = Get-SlipwayE2EVessel -Id $id
	$expectedBlocks = 81 + $specs.Count
	Add-SlipwayE2ECheck -Run $run -Title 'helm use assembles the ship' -Condition ([int]$vessel.blocks -eq $expectedBlocks) -Detail "use: $use; vessel $id blocks=$($vessel.blocks) (expected $expectedBlocks)" | Out-Null
	$worldCleared = (Test-SlipwayE2EBlock -X $hx -Y $hy -Z $hz -Block 'minecraft:air') -and (Test-SlipwayE2EBlock -X ($hx - 3) -Y $hy -Z ($hz - 3) -Block 'minecraft:air') -and
		(Test-SlipwayE2EBlock -X $hx -Y ($hy - 1) -Z $hz -Block 'minecraft:air')
	Add-SlipwayE2ECheck -Run $run -Title 'world blocks were moved out' -Condition $worldCleared | Out-Null
	$pa = $vessel.PlotAnchor
	$inPlot = Test-SlipwayE2EShipAt -Specs $specs -X $pa[0] -Y $pa[1] -Z $pa[2] -ReferenceData $reference
	Add-SlipwayE2ECheck -Run $run -Title 'plot holds every block with identical state and block-entity data' -Condition ($inPlot.Count -eq 0) -Detail ($(if ($inPlot.Count) { $inPlot -join '; ' } else { "plot anchor $($pa -join ',')" })) | Out-Null
	Start-Sleep -Seconds 1
	$rider = Get-SlipwayE2ERider -Client $c
	Add-SlipwayE2ECheck -Run $run -Title 'the player stands on the new vessel' -Condition ($rider.Carrier -eq $id -and $rider.onGround -eq 'true') -Detail $rider.Text | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '02-assembled' -Directory $run.Directory -SettleMilliseconds 800
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: assembled vessel (third person)' -Screenshot $shot
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null

	# Pilot it somewhere else, turning about 90 degrees.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 200
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	Start-Sleep -Milliseconds 500
	$state = Invoke-SlipwayE2EAgent -Client $c -Verb state
	Add-SlipwayE2ECheck -Run $run -Title 'using the helm on the vessel takes the helm' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null
	$start = Get-SlipwayE2EVessel -Id $id
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'helm 1 0 0.3 0 0.8 0 60' | Out-Null
	Start-Sleep -Seconds 2
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '03-piloting' -Directory $run.Directory
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: piloting with HUD' -Screenshot $shot
	Start-Sleep -Seconds 5
	$moved = Get-SlipwayE2EVessel -Id $id
	$distance = [Math]::Sqrt([Math]::Pow($moved.X - $start.X, 2) + [Math]::Pow($moved.Z - $start.Z, 2))
	$turn = [Math]::Abs((($moved.yaw - $start.yaw + 540) % 360) - 180)
	Add-SlipwayE2ECheck -Run $run -Title 'the vessel flew and turned' -Condition ($distance -gt 4 -and $turn -gt 30 -and $moved.tilt -lt 10) -Detail ("moved {0:N1} blocks, turned {1:N1} deg, tilt {2:N1}, speed {3:N2}" -f $distance, $turn, $moved.tilt, $moved.speed) | Out-Null

	# Leave the helm, then sneak and use it to disassemble.
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 6' | Out-Null
	Start-Sleep -Seconds 1
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 40' | Out-Null
	Start-Sleep -Milliseconds 400
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 200
	$logOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
	$final = Get-SlipwayE2EVessel -Id $id
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	$line = @(Wait-SlipwayE2ELog -LogPath $s.LogPath -Pattern "(Disassembled vessel $id \(|Vessel $id stays assembled)" -StartOffset $logOffset -TimeoutSeconds 10)[-1]
	if ($line -match 'stays assembled') { throw "Disassembly refused: $line" }
	$m = [regex]::Match($line, 'at (-?\d+), (-?\d+), (-?\d+) with (\d) quarter turns')
	$ax = [long]$m.Groups[1].Value; $ay = [long]$m.Groups[2].Value; $az = [long]$m.Groups[3].Value; $turns = [int]$m.Groups[4].Value
	$gone = [string](Send-SlipwayE2ERcon -Command "slipway info $id")
	Add-SlipwayE2ECheck -Run $run -Title 'sneak + use on the helm disassembles' -Condition ($m.Success -and $gone -match 'no vessel') -Detail "helm at $ax $ay $az, $turns quarter turns (vessel yaw was $($final.yaw))" | Out-Null
	$after = Test-SlipwayE2EShipAt -Specs $specs -X $ax -Y $ay -Z $az -QuarterTurns $turns -ReferenceData $reference
	Add-SlipwayE2ECheck -Run $run -Title 'every block is back with turned state and identical block-entity data' -Condition ($after.Count -eq 0) -Detail ($after -join '; ') | Out-Null
	$missingDeck = @(foreach ($dx in -4..4) { foreach ($dz in -4..4) {
		$o = Get-SlipwayE2ETurnedOffset -Dx $dx -Dz $dz -QuarterTurns $turns
		if (-not (Test-SlipwayE2EBlock -X ($ax + $o[0]) -Y ($ay - 1) -Z ($az + $o[1]) -Block 'minecraft:oak_planks')) { "$($ax + $o[0]) $($ay - 1) $($az + $o[1])" }
	} })
	Add-SlipwayE2ECheck -Run $run -Title 'the whole deck is back' -Condition ($missingDeck.Count -eq 0) -Detail $(if ($missingDeck.Count) { "missing planks at $($missingDeck -join '; ')" } else { '81 planks' }) | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 0' | Out-Null
	Start-Sleep -Milliseconds 500
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shot = Save-SlipwayE2EScreenshot -Client $c -Name '04-disassembled' -Directory $run.Directory -SettleMilliseconds 800
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: disassembled ship back in the world' -Screenshot $shot
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M2'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
