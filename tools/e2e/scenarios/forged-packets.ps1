# Scenario forged-packets: a real client sends forged and malformed serverbound packets and the server refuses them:
# helm control for a vessel the sender is not piloting, for another vessel than the one piloted, with NaN and infinite
# axes, out-of-range axes (clamped), a flood above the rate limit, and use/break packets aimed at vessel blocks from
# far away (reach is measured to where the vessel is).
param([switch]$NewWorld, [switch]$StopAfter)
. (Join-Path $PSScriptRoot '_common.ps1')

$session = Start-SlipwayE2ESession -NewWorld:$NewWorld
$s = $session.Server; $c = $session.Client
$run = New-SlipwayE2ERun -Name 'forged-packets'
$serverOffset = Get-SlipwayE2ELogOffset -LogPath $s.LogPath
$clientOffset = Get-SlipwayE2ELogOffset -LogPath $c.ClientLog

function Get-Rejected { [long]([regex]::Match([string](Send-SlipwayE2ERcon -Command 'slipway packets'), '(\d+)').Groups[1].Value) }
function Send-Forged([string]$Arguments) { Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "forgeHelm $Arguments" | Out-Null; Start-Sleep -Milliseconds 400 }
function Get-Input([long]$Id) { (Get-SlipwayE2EVessel -Id $Id).input }

try {
	$hx = 0; $hy = 150; $hz = 3100
	Reset-SlipwayE2EScenario -Client $c
	Enter-SlipwayE2EArea -Client $c -X ($hx + 0.5) -Y $hy -Z ($hz - 1.5) -NoPlatform -SettleSeconds 3
	Clear-SlipwayE2EVolume ($hx - 30) ($hy - 10) ($hz - 30) ($hx + 60) ($hy + 10) ($hz + 30)
	New-SlipwayE2EDeck -X $hx -Y $hy -Z $hz -HalfX 3 -HalfZ 3
	Send-SlipwayE2ERcon -Command "setblock $hx $hy $hz slipway:helm[facing=north]" | Out-Null
	$a = Invoke-SlipwayE2EAssembleCommand -X $hx -Y $hy -Z $hz
	New-SlipwayE2EDeck -X ($hx + 40) -Y $hy -Z $hz -HalfX 3 -HalfZ 3
	Send-SlipwayE2ERcon -Command "setblock $($hx + 40) $hy $hz slipway:helm[facing=north]" | Out-Null
	$b = Invoke-SlipwayE2EAssembleCommand -X ($hx + 40) -Y $hy -Z $hz
	Start-Sleep -Seconds 2

	# 1. Helm control while not at any helm.
	$r0 = Get-Rejected
	Send-Forged "$a 1 0 0 0 0 0 0"
	$r1 = Get-Rejected
	Add-SlipwayE2ECheck -Run $run -Title 'helm control from a player who is not at a helm is refused' -Condition ($r1 -eq $r0 + 1 -and (Get-Input $a) -eq '0.00,0.00,0.00,0.00,0.00,0.00') -Detail "rejected $r0 -> $r1; input $(Get-Input $a)" | Out-Null

	# Take the helm of A.
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "lookAtLocal $a 0.5 0.5 0.5" | Out-Null
	Start-Sleep -Milliseconds 200
	Invoke-SlipwayE2EAgent -Client $c -Verb use | Out-Null
	Start-Sleep -Milliseconds 500
	$state = Invoke-SlipwayE2EAgent -Client $c -Verb state
	Add-SlipwayE2ECheck -Run $run -Title 'the player pilots vessel A' -Condition ($state -match 'vehicle=vessel') -Detail $state | Out-Null

	# 2. Control for another vessel.
	$r0 = Get-Rejected
	Send-Forged "$b 1 0 0 0 0 0 0"
	$r1 = Get-Rejected
	Add-SlipwayE2ECheck -Run $run -Title 'helm control for a vessel the player is not piloting is refused' -Condition ($r1 -eq $r0 + 1 -and (Get-Input $b) -eq '0.00,0.00,0.00,0.00,0.00,0.00') -Detail "rejected $r0 -> $r1; input of B $(Get-Input $b)" | Out-Null

	# 3. NaN and infinities.
	$r0 = Get-Rejected
	Send-Forged "$a NaN 0 0 0 0 0 0"
	Send-Forged "$a 0 Infinity 0 0 0 0 0"
	Send-Forged "$a 0 0 0 -Infinity 0 0 0"
	$r1 = Get-Rejected
	Add-SlipwayE2ECheck -Run $run -Title 'NaN and infinite axes are refused' -Condition ($r1 -eq $r0 + 3) -Detail "rejected $r0 -> $r1; input $(Get-Input $a)" | Out-Null

	# 4. Out of range: accepted but clamped to [-1, 1].
	$r0 = Get-Rejected
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "forgeHelm $a 5 -7 0.5 1000000 -3 0 0" | Out-Null
	Start-Sleep -Milliseconds 150
	$input = Get-Input $a
	$r1 = Get-Rejected
	Add-SlipwayE2ECheck -Run $run -Title 'out-of-range axes are clamped to [-1, 1]' -Condition ($r1 -eq $r0 -and $input -eq '1.00,-1.00,0.50,1.00,-1.00,0.00') -Detail "input $input; rejected $r0 -> $r1" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "forgeHelm $a 0 0 0 0 0 0 0" | Out-Null

	# 5. Flood: 200 packets in one tick; at most 40 a second are accepted.
	Start-Sleep -Seconds 2
	$r0 = Get-Rejected
	Invoke-SlipwayE2EAgent -Client $c -Verb slipway -Argument "forgeHelmBurst $a 200" | Out-Null
	Start-Sleep -Milliseconds 800
	$r1 = Get-Rejected
	Add-SlipwayE2ECheck -Run $run -Title 'a flood of control packets is rate limited' -Condition (($r1 - $r0) -ge 150) -Detail "rejected $($r1 - $r0) of 200" | Out-Null

	# 6. Use and break aimed at vessel B's blocks (plot coordinates) from 40 blocks away.
	Invoke-SlipwayE2EAgent -Client $c -Verb hold -Argument 'key.keyboard.left.shift 6' | Out-Null
	Start-Sleep -Seconds 1
	Send-SlipwayE2ERcon -Command ("item replace entity {0} weapon.mainhand with minecraft:stone 16" -f $c.Player) | Out-Null
	Start-Sleep -Milliseconds 300
	$vb = Get-SlipwayE2EVessel -Id $b
	$px = $vb.PlotAnchor[0] + 1; $py = $vb.PlotAnchor[1] - 1; $pz = $vb.PlotAnchor[2] + 1
	Invoke-SlipwayE2EAgent -Client $c -Verb forgeuse -Argument "$px $py $pz up" | Out-Null
	Start-Sleep -Milliseconds 600
	$placed = Test-SlipwayE2EBlock -X $px -Y ($py + 1) -Z $pz -Block 'minecraft:stone'
	Add-SlipwayE2ECheck -Run $run -Title 'a use packet on a vessel block out of reach is refused' -Condition (-not $placed -and (Test-SlipwayE2EBlock -X $px -Y ($py + 1) -Z $pz -Block 'minecraft:air')) -Detail "target plot $px $py $pz (vessel B is 40 blocks away)" | Out-Null
	Invoke-SlipwayE2EAgent -Client $c -Verb forgebreak -Argument "$px $py $pz" | Out-Null
	Start-Sleep -Milliseconds 600
	Add-SlipwayE2ECheck -Run $run -Title 'a break packet on a vessel block out of reach is refused' -Condition (Test-SlipwayE2EBlock -X $px -Y $py -Z $pz -Block 'minecraft:oak_planks') -Detail "target plot $px $py $pz" | Out-Null
	# The same use on vessel A, which the player stands next to, is allowed (reach is measured to the vessel in the world).
	$va = Get-SlipwayE2EVessel -Id $a
	$qx = $va.PlotAnchor[0] + 1; $qy = $va.PlotAnchor[1] - 1; $qz = $va.PlotAnchor[2] - 2
	Invoke-SlipwayE2EAgent -Client $c -Verb forgeuse -Argument "$qx $qy $qz up" | Out-Null
	Start-Sleep -Milliseconds 600
	Add-SlipwayE2ECheck -Run $run -Title 'the same packet on a vessel block within reach works' -Condition (Test-SlipwayE2EBlock -X $qx -Y ($qy + 1) -Z $qz -Block 'minecraft:stone') -Detail "target plot $qx $qy $qz on vessel A next to the player" | Out-Null
	$logged = @(Read-SlipwayE2ELogSince -LogPath $s.LogPath -Offset $serverOffset | Where-Object { $_ -match 'Rejected helm control' })
	Add-SlipwayE2ECheck -Run $run -Title 'refusals are logged, rate limited' -Condition ($logged.Count -ge 1 -and $logged.Count -le 20) -Detail (($logged | Select-Object -First 4) -join ' / ') | Out-Null
} catch {
	Add-SlipwayE2EStep -Run $run -Title 'scenario error' -Detail "$($_.Exception.Message) at $($_.InvocationInfo.PositionMessage)" -Status fail
} finally {
	$result = Complete-SlipwayE2EScenario -Run $run -Session $session -ServerOffset $serverOffset -ClientOffset $clientOffset -Milestone 'M6'
	if ($StopAfter) { Stop-SlipwayE2ESession | Out-Null }
}
$result
