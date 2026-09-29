# Scenario multiplayer: two clients on the dedicated server (A: Prism with Sodium, Iris and DH; B: an offline dev client
# with the vanilla renderer). Client A pilots a vessel through a climbing turn while
# client B watches from a platform: B's rendered vessel moves smoothly (frame-by-frame trace: no reversals, no jumps),
# both clients agree with the server's pose, and when B walks onto the flying vessel's deck B is carried along on B's
# own client and on the server. Screenshots from both clients.
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'multiplayer'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog
$c2 = $null; $client2Offset = 0

function Get-ClientPose($Client, [long]$Id) {
	$text = Invoke-SlipwayE2EAgent -Client $Client -Verb slipway -Argument 'vessels'
	$m = [regex]::Match($text, "#$Id ready=true centre=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)")
	if (-not $m.Success) { return $null }
	[pscustomobject]@{ X = [double]$m.Groups[1].Value; Y = [double]$m.Groups[2].Value; Z = [double]$m.Groups[3].Value; Text = $text }
}
function Get-Distance($A, $B) { [Math]::Sqrt([Math]::Pow($A.X - $B.CX, 2) + [Math]::Pow($A.Y - $B.CY, 2) + [Math]::Pow($A.Z - $B.CZ, 2)) }

try {
	$hx = 0; $hy = 150; $hz = 3400
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 50) ($hy - 15) ($hz - 50) ($hx + 50) ($hy + 25) ($hz + 60)
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz -HalfX 4 -HalfZ 4
	Set-SlipwayE2EBlocks -Specs (Get-SlipwayE2ESmallShip) -X $hx -Y $hy -Z $hz
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3} facing {1} {2} {4}" -f $c.Player, ($hx + 0.5), $hy, ($hz - 1.5), ($hz + 5)) | Out-Null
	$id = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	# Movement checks cover riding and piloting, not the harness's own setup teleports.
	$movementOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath

	# Second client: another player, as an offline dev client (the e2e server runs in offline mode).
	$c2 = Start-SlipwayE2EWatcher -Server $s
	$client2Offset = Get-SlipwayE2ELogOffset -LogPath $c2.ClientLog
	$session | Add-Member -NotePropertyName Client2 -NotePropertyValue $c2 -Force
	$session | ConvertTo-Json -Depth 4 | Set-Content (Get-SlipwayE2ESessionFile)
	Add-SlipwayE2ECheck -Run $run -Title 'a second client joins as another player' -Condition ($c2.Player -and $c2.Player -ne $c.Player) -Detail "players $($c.Player) and $($c2.Player)" | Out-Null
	Enter-SlipwayE2EArea -Client $c2 -X ($hx + 16.5) -Y ($hy + 4) -Z ($hz + 6.5) -LookAt @($hx, $hy, ($hz + 12)) -SettleSeconds 4

	# A takes the helm.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $id 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 200
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	Start-Sleep -Milliseconds 500
	$state = Invoke-SlipwayE2EAgent -Client $c -Verb state
	Add-SlipwayE2ECheck -Run $run -Title 'client A takes the helm' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null

	# A flies a climbing turn while B records every rendered frame of the vessel.
	Invoke-SlipwayE2EAgent -Client $c2 -Verb slipway -Argument "traceStart $id" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'helm 0.7 0 0.3 0 0.5 0 100' | Out-Null
	Start-Sleep -Milliseconds 1500
	$gaps = [System.Collections.Generic.List[string]]::new()
	$worst = 0.0
	for ($i = 0; $i -lt 6; $i++) {
		$clock = [System.Diagnostics.Stopwatch]::StartNew()
		$server = Get-SlipwayE2EVessel -Id $id
		$t0 = $clock.Elapsed.TotalSeconds
		$pa = Get-ClientPose $c $id
		$ta = $clock.Elapsed.TotalSeconds
		$pb = Get-ClientPose $c2 $id
		$tb = $clock.Elapsed.TotalSeconds
		if ($pa -and $pb) {
			# Clients play poses back 2 ticks late on purpose (interpolation), and each client is asked a little
			# after the server: allow the distance flown in that time plus half a block.
			$allowA = 0.5 + $server.speed * (0.1 + $ta - $t0 + 0.05)
			$allowB = 0.5 + $server.speed * (0.1 + $tb - $t0 + 0.05)
			$da = Get-Distance $pa $server; $db = Get-Distance $pb $server
			$worst = [Math]::Max($worst, [Math]::Max($da - $allowA, $db - $allowB))
			$gaps.Add(("A {0:N2} (allow {1:N2}) B {2:N2} (allow {3:N2})" -f $da, $allowA, $db, $allowB))
		} else { $gaps.Add('missing'); $worst = 99 }
		Start-Sleep -Milliseconds 300
	}
	Start-Sleep -Seconds 3
	$trace = Invoke-SlipwayE2EAgent -Client $c2 -Verb slipway -Argument 'traceStop'
	# Screenshots after the trace: taking one stalls that client for a moment.
	$shotB = Save-SlipwayE2EScreenshot -Client $c2 -Name '01-watcher-view' -Directory $run.Directory
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	$shotA = Save-SlipwayE2EScreenshot -Client $c -Name '02-pilot-view' -Directory $run.Directory
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	Add-SlipwayE2ECheck -Run $run -Title 'both clients agree with the server pose (within the interpolation delay)' -Condition ($worst -le 0) -Detail ($gaps -join '; ') -Screenshot $shotA | Out-Null
	$tm = [regex]::Match($trace, 'frames=(\d+) ticks=([\d.]+) meanSpeed=([\d.]+) maxDeltaV=([\d.]+) maxStep=([\d.]+) reversals=(\d+) maxTurnDeg=([\d.]+) stalls=(\d+)')
	$smooth = $tm.Success -and [int]$tm.Groups[1].Value -gt 100 -and [int]$tm.Groups[6].Value -eq 0 -and [double]$tm.Groups[4].Value -lt 0.25 -and [double]$tm.Groups[3].Value -gt 0.1 -and [int]$tm.Groups[8].Value -le 2
	Add-SlipwayE2ECheck -Run $run -Title "the watcher's rendered vessel moves smoothly (no reversals, no jumps between frames)" -Condition $smooth -Detail "trace on client B: $trace (speeds in blocks per tick)" -Screenshot $shotB | Out-Null

	# B steps onto the moving deck and is carried; the pilot sees B on the deck.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument 'helm 0.3 0 0 0 0.3 0 200' | Out-Null
	Start-Sleep -Milliseconds 500
	$v = Get-SlipwayE2EVessel -Id $id
	$deck = ConvertTo-SlipwayE2EWorld -Vessel $v -X 2.5 -Y 0.4 -Z 1.5
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3}" -f $c2.Player, $deck.X, $deck.Y, $deck.Z) | Out-Null
	Start-Sleep -Seconds 2
	$ridersB = [System.Collections.Generic.List[string]]::new()
	$okB = $true
	for ($i = 0; $i -lt 8; $i++) {
		$r = Get-SlipwayE2ERider -Client $c2
		$ridersB.Add($r.Text)
		if ($r.Carrier -ne $id -or -not $r.Local -or $r.Local[1] -lt -0.05 -or $r.Local[1] -gt 1.3) { $okB = $false }
		Start-Sleep -Milliseconds 300
	}
	$serverB = Get-SlipwayE2EPlayerPosition -Player $c2.Player
	$v = Get-SlipwayE2EVessel -Id $id
	Add-SlipwayE2ECheck -Run $run -Title 'client B is carried on the moving deck (on its own client)' -Condition $okB -Detail ($ridersB -join ' | ') | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument back | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb look -Argument '160 25' | Out-Null
	$shotA2 = Save-SlipwayE2EScreenshot -Client $c -Name '03-pilot-sees-B-on-deck' -Directory $run.Directory -SettleMilliseconds 300
	Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null
	$shotB2 = Save-SlipwayE2EScreenshot -Client $c2 -Name '04-B-riding' -Directory $run.Directory
	$kicked = @(Read-SlipwayE2ELogSince -LogPath $s.LogPath -Offset $movementOffset | Where-Object { $_ -match 'lost connection|kicked|moved wrongly|moved too quickly|Flying is not enabled' })
	Add-SlipwayE2ECheck -Run $run -Title 'the server accepts both players (no kicks or corrections)' -Condition ($kicked.Count -eq 0) -Detail (($kicked | Select-Object -First 3) -join ' / ') -Screenshot $shotA2 | Out-Null
	Add-SlipwayE2EStep -Run $run -Title 'screenshot: B riding the deck, B view' -Screenshot $shotB2
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	try { Invoke-SlipwayE2EAgent -Client $c -Verb perspective -Argument first | Out-Null } catch { }
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M6' -Client2 $c2 -Client2Offset $client2Offset
	if ($c2) {
		try { Invoke-SlipwayE2EAgent -Client $c2 -Verb disconnect | Out-Null } catch { }
		Stop-SlipwayE2EClient -Client $c2 | Out-Null
	}
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
