# Shared helpers for Slipway end-to-end scenarios; dot-source from a scenario script:
#   . (Join-Path $PSScriptRoot '_common.ps1')
#
# A scenario reuses the running e2e server and client (recorded in E:\slipway-e2e\session.json) when they are
# alive and on the requested world, and starts them otherwise, so scenarios can run one after another quickly.
# Every scenario builds in its own area of the world, well apart from the others.

Import-Module (Join-Path $PSScriptRoot '..\SlipwayE2E.psm1') -Force
$ErrorActionPreference = 'Stop'

function Get-SlipwayE2ESessionFile { Join-Path (Get-SlipwayE2ERoot) 'session.json' }

function Test-SlipwayE2EAlive { param($Process) $Process -and (Get-Process -Id $Process.ProcessId -ErrorAction SilentlyContinue) }

function Start-SlipwayE2ESession {
	<# .SYNOPSIS Returns a live server + client session, starting (or restarting) them when needed. #>
	param([string]$World = 'e2e', [switch]$NewWorld, [bool]$Shaders = $false, [int]$RenderDistance = 10, [switch]$Restart)
	$file = Get-SlipwayE2ESessionFile
	if (Test-Path $file) {
		$old = Get-Content $file -Raw | ConvertFrom-Json
		$reuse = -not $NewWorld -and -not $Restart -and (Test-SlipwayE2EAlive $old.Server) -and (Test-SlipwayE2EAlive $old.Client) -and $old.Server.World -eq $World -and
			[bool]$old.Shaders -eq $Shaders -and [int]$old.RenderDistance -eq $RenderDistance
		if ($reuse) { return $old }
		Stop-SlipwayE2ESession | Out-Null
	}
	if ($NewWorld) { New-SlipwayE2EWorld -Name $World | Out-Null }
	$server = Start-SlipwayE2EServer -World $World
	Set-SlipwayE2EShaders -Enabled $Shaders
	Set-SlipwayE2EOptions -RenderDistance $RenderDistance
	try {
		$client = Start-SlipwayE2EClient -Server $server
	} catch {
		Stop-SlipwayE2EServer -Server $server | Out-Null
		throw
	}
	$session = [pscustomobject]@{ Server = $server; Client = $client; Shaders = $Shaders; RenderDistance = $RenderDistance }
	$session | ConvertTo-Json -Depth 4 | Set-Content $file
	Initialize-SlipwayE2EWorld
	$session
}

function Stop-SlipwayE2ESession {
	$file = Get-SlipwayE2ESessionFile
	if (-not (Test-Path $file)) { return }
	$old = Get-Content $file -Raw | ConvertFrom-Json
	$clients = @($old.Client) + @($(if ($old.PSObject.Properties['Client2']) { $old.Client2 } else { $null })) | Where-Object { Test-SlipwayE2EAlive $_ }
	$server = if (Test-SlipwayE2EAlive $old.Server) { $old.Server } else { $null }
	$result = Stop-SlipwayE2EAll -Server $server -Clients @($clients)
	Remove-Item $file -Force
	$result
}

function Initialize-SlipwayE2EWorld {
	foreach ($cmd in 'time set noon', 'gamerule advance_time false', 'weather clear', 'gamerule advance_weather false', 'difficulty peaceful') {
		Send-SlipwayE2ERcon -Command $cmd | Out-Null
	}
}

function Enter-SlipwayE2EArea {
	<# .SYNOPSIS Puts the player on a small glass platform at X Y Z (feet), facing a point, and waits for chunks. #>
	param([Parameter(Mandatory)]$Client, [Parameter(Mandatory)][double]$X, [Parameter(Mandatory)][double]$Y, [Parameter(Mandatory)][double]$Z,
		[double[]]$LookAt, [switch]$NoPlatform, [int]$SettleSeconds = 3)
	$bx = [Math]::Floor($X); $by = [Math]::Floor($Y) - 1; $bz = [Math]::Floor($Z)
	Send-SlipwayE2ERcon -Command "gamemode creative $($Client.Player)" | Out-Null
	# Hover while the destination loads: falling into chunks that are still arriving trips the server's movement check.
	try { Invoke-SlipwayE2EAgent -Client $Client -Verb fly -Argument on | Out-Null } catch { }
	$face = if ($LookAt) { " facing $($LookAt[0]) $($LookAt[1]) $($LookAt[2])" } else { '' }
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3}{4}" -f $Client.Player, $X, ($Y + 0.5), $Z, $face) | Out-Null
	Start-Sleep -Seconds $SettleSeconds
	if (-not $NoPlatform) {
		Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} minecraft:glass" -f ($bx - 1), $by, ($bz - 1), ($bx + 1), ($bz + 1)) | Out-Null
	}
	Send-SlipwayE2ERcon -Command ("tp {0} {1} {2} {3}{4}" -f $Client.Player, $X, $Y, $Z, $face) | Out-Null
	try { Invoke-SlipwayE2EAgent -Client $Client -Verb fly -Argument off | Out-Null } catch { }
	Start-Sleep -Milliseconds 800
}

function Clear-SlipwayE2EVolume {
	<# .SYNOPSIS Fills a box with air in slabs (fill is limited to 32768 blocks per command). #>
	param([long]$X1, [long]$Y1, [long]$Z1, [long]$X2, [long]$Y2, [long]$Z2, [string]$Block = 'minecraft:air')
	$area = ([Math]::Abs($X2 - $X1) + 1) * ([Math]::Abs($Z2 - $Z1) + 1)
	$step = [Math]::Max(1, [Math]::Floor(32768 / $area))
	for ($y = [Math]::Min($Y1, $Y2); $y -le [Math]::Max($Y1, $Y2); $y += $step) {
		$top = [Math]::Min([Math]::Max($Y1, $Y2), $y + $step - 1)
		Send-SlipwayE2ERcon -Command "fill $X1 $y $Z1 $X2 $top $Z2 $Block" | Out-Null
	}
}

function Remove-SlipwayE2EVessels {
	<# .SYNOPSIS Deletes every vessel in the e2e world (scenarios start from a clean slate). #>
	$list = [string](Send-SlipwayE2ERcon -Command 'slipway list')
	foreach ($m in [regex]::Matches($list, '#(\d+) blocks=')) {
		Send-SlipwayE2ERcon -Command "slipway remove $($m.Groups[1].Value)" | Out-Null
	}
}

function Reset-SlipwayE2EScenario {
	<# .SYNOPSIS Clean slate for a scenario: no vessels, no stray items, the player in creative with empty hands. #>
	param([Parameter(Mandatory)]$Client)
	Remove-SlipwayE2EVessels
	foreach ($cmd in "gamemode creative $($Client.Player)", "clear $($Client.Player)", 'kill @e[type=item]', "effect clear $($Client.Player)") {
		Send-SlipwayE2ERcon -Command $cmd | Out-Null
	}
	try { Invoke-SlipwayE2EAgent -Client $Client -Verb select -Argument 0 | Out-Null; Invoke-SlipwayE2EAgent -Client $Client -Verb perspective -Argument first | Out-Null } catch { }
}

function Get-SlipwayE2ENewVesselId {
	<# .SYNOPSIS Reads the id of the vessel assembled after a server log offset. #>
	param([Parameter(Mandatory)]$Server, [long]$Offset, [int]$TimeoutSeconds = 10)
	$line = @(Wait-SlipwayE2ELog -LogPath $Server.LogPath -Pattern 'Assembled vessel (\d+) from' -StartOffset $Offset -TimeoutSeconds $TimeoutSeconds)[-1]
	[long]([regex]::Match($line, 'Assembled vessel (\d+) from').Groups[1].Value)
}

function Invoke-SlipwayE2EAssembleCommand {
	<# .SYNOPSIS Assembles through /slipway assemble and returns the new id. #>
	param([Parameter(Mandatory)][long]$X, [Parameter(Mandatory)][long]$Y, [Parameter(Mandatory)][long]$Z)
	$text = [string](Send-SlipwayE2ERcon -Command "slipway assemble $X $Y $Z")
	if ($text -notmatch 'id=(\d+)') { throw "Assembly failed: $text" }
	[long]$matches[1]
}

# ---------------------------------------------------------------------------------------------------------------
# Block specs: offsets from the helm, a block id, horizontal-facing or 16-step rotation properties and NBT.
# ---------------------------------------------------------------------------------------------------------------

function New-SlipwayE2EBlock {
	param([string]$Name, [int]$Dx, [int]$Dy, [int]$Dz, [string]$Block, [System.Collections.Specialized.OrderedDictionary]$Props = [ordered]@{}, [string]$Nbt = '')
	[pscustomobject]@{ Name = $Name; Dx = $Dx; Dy = $Dy; Dz = $Dz; Block = $Block; Props = $Props; Nbt = $Nbt }
}

function Get-SlipwayE2EStateString {
	<# .SYNOPSIS block[props] for a spec after some counterclockwise quarter turns. #>
	param([Parameter(Mandatory)]$Spec, [int]$QuarterTurns = 0)
	if ($Spec.Props.Count -eq 0) { return $Spec.Block }
	$ccw = @{ north = 'west'; west = 'south'; south = 'east'; east = 'north' }
	$parts = foreach ($k in $Spec.Props.Keys) {
		$v = [string]$Spec.Props[$k]
		for ($i = 0; $i -lt (($QuarterTurns % 4) + 4) % 4; $i++) {
			if ($k -eq 'facing' -and $ccw.ContainsKey($v)) { $v = $ccw[$v] }
			elseif ($k -eq 'rotation') { $v = [string]((([int]$v) + 12) % 16) }
		}
		"$k=$v"
	}
	"$($Spec.Block)[$($parts -join ',')]"
}

function Get-SlipwayE2ETurnedOffset {
	<# .SYNOPSIS A local offset turned by counterclockwise quarter turns about +Y (Slipway's convention: +X to -Z). #>
	param([int]$Dx, [int]$Dz, [int]$QuarterTurns)
	switch ((($QuarterTurns % 4) + 4) % 4) {
		1 { return @($Dz, -$Dx) }
		2 { return @(-$Dx, -$Dz) }
		3 { return @(-$Dz, $Dx) }
		default { return @($Dx, $Dz) }
	}
}

function Set-SlipwayE2EBlocks {
	param([Parameter(Mandatory)][object[]]$Specs, [long]$X, [long]$Y, [long]$Z)
	foreach ($b in $Specs) {
		$r = [string](Send-SlipwayE2ERcon -Command ("setblock {0} {1} {2} {3}{4}" -f ($X + $b.Dx), ($Y + $b.Dy), ($Z + $b.Dz), (Get-SlipwayE2EStateString $b), $b.Nbt))
		if ($r -notmatch 'Changed the block') { throw "setblock for $($b.Name) failed: $r" }
	}
}

function Get-SlipwayE2ESmallShip {
	<# .SYNOPSIS A 7x7 test ship with orientation markers: helm, mast with a red flag at the bow, chest and lantern aft. #>
	@(
		New-SlipwayE2EBlock 'helm' 0 0 0 'slipway:helm' ([ordered]@{ facing = 'north' })
		New-SlipwayE2EBlock 'mast1' 0 0 3 'minecraft:oak_log' ([ordered]@{ axis = 'y' })
		New-SlipwayE2EBlock 'mast2' 0 1 3 'minecraft:oak_log' ([ordered]@{ axis = 'y' })
		New-SlipwayE2EBlock 'mast3' 0 2 3 'minecraft:oak_log' ([ordered]@{ axis = 'y' })
		New-SlipwayE2EBlock 'flag' 1 2 3 'minecraft:red_wool'
		New-SlipwayE2EBlock 'chest' -2 0 -2 'minecraft:chest' ([ordered]@{ facing = 'south'; type = 'single' }) '{Items:[{Slot:0b,id:"minecraft:gold_ingot",count:5}]}'
		New-SlipwayE2EBlock 'lantern' 2 0 -2 'minecraft:lantern' ([ordered]@{ hanging = 'false' })
		New-SlipwayE2EBlock 'rail1' -3 0 3 'minecraft:oak_fence'
		New-SlipwayE2EBlock 'rail2' 3 0 3 'minecraft:oak_fence'
	)
}

function Get-SlipwayE2EMixedShip {
	<# .SYNOPSIS The mixed test ship: 9x9 oak deck under the helm, chest with items, furnace, door, sign, lamp + lever, stairs, torch. #>
	@(
		New-SlipwayE2EBlock 'helm' 0 0 0 'slipway:helm' ([ordered]@{ facing = 'north' })
		New-SlipwayE2EBlock 'chest' -3 0 -3 'minecraft:chest' ([ordered]@{ facing = 'south'; type = 'single' }) '{Items:[{Slot:0b,id:"minecraft:diamond",count:7},{Slot:13b,id:"minecraft:oak_log",count:32},{Slot:26b,id:"minecraft:name_tag",count:1,components:{"minecraft:custom_name":"Slipway"}}]}'
		New-SlipwayE2EBlock 'furnace' -3 0 -1 'minecraft:furnace' ([ordered]@{ facing = 'east'; lit = 'false' }) '{Items:[{Slot:1b,id:"minecraft:coal",count:3},{Slot:2b,id:"minecraft:iron_ingot",count:9}]}'
		New-SlipwayE2EBlock 'door-lower' 3 0 -3 'minecraft:oak_door' ([ordered]@{ facing = 'south'; half = 'lower'; hinge = 'left'; open = 'false' })
		New-SlipwayE2EBlock 'door-upper' 3 1 -3 'minecraft:oak_door' ([ordered]@{ facing = 'south'; half = 'upper'; hinge = 'left'; open = 'false' })
		New-SlipwayE2EBlock 'sign' 3 0 3 'minecraft:oak_sign' ([ordered]@{ rotation = '4' }) '{front_text:{messages:["Slipway","e2e test","",""]}}'
		New-SlipwayE2EBlock 'lamp' -3 0 3 'minecraft:redstone_lamp' ([ordered]@{ lit = 'false' })
		New-SlipwayE2EBlock 'lever' -3 1 3 'minecraft:lever' ([ordered]@{ face = 'floor'; facing = 'north'; powered = 'false' })
		New-SlipwayE2EBlock 'stairs' 1 0 -3 'minecraft:oak_stairs' ([ordered]@{ facing = 'east'; half = 'bottom' })
		New-SlipwayE2EBlock 'torch' 2 0 2 'minecraft:torch'
	)
}

function New-SlipwayE2EDeck {
	<# .SYNOPSIS Fills a deck one block under the helm: HalfX/HalfZ blocks each side. #>
	param([long]$X, [long]$Y, [long]$Z, [int]$HalfX = 4, [int]$HalfZ = 4, [string]$Block = 'minecraft:oak_planks')
	Send-SlipwayE2ERcon -Command ("fill {0} {1} {2} {3} {1} {4} {5}" -f ($X - $HalfX), ($Y - 1), ($Z - $HalfZ), ($X + $HalfX), ($Z + $HalfZ), $Block) | Out-Null
}

function Test-SlipwayE2EShipAt {
	<# .SYNOPSIS Checks every spec block (exact state, and block-entity data) at a helm position after quarter turns. #>
	param([Parameter(Mandatory)][object[]]$Specs, [long]$X, [long]$Y, [long]$Z, [int]$QuarterTurns = 0, [hashtable]$ReferenceData = @{})
	$problems = [System.Collections.Generic.List[string]]::new()
	foreach ($b in $Specs) {
		$o = Get-SlipwayE2ETurnedOffset -Dx $b.Dx -Dz $b.Dz -QuarterTurns $QuarterTurns
		$px = $X + $o[0]; $py = $Y + $b.Dy; $pz = $Z + $o[1]
		$state = Get-SlipwayE2EStateString $b $QuarterTurns
		if (-not (Test-SlipwayE2EBlock -X $px -Y $py -Z $pz -Block $state)) { $problems.Add("$($b.Name): expected $state at $px $py $pz"); continue }
		if ($ReferenceData.ContainsKey($b.Name)) {
			$data = Get-SlipwayE2EBlockData -X $px -Y $py -Z $pz
			if ($data -ne $ReferenceData[$b.Name]) { $problems.Add("$($b.Name): block data differs: $data vs $($ReferenceData[$b.Name])") }
		}
	}
	,$problems
}

function Get-SlipwayE2EBlockEntityData {
	param([Parameter(Mandatory)][object[]]$Specs, [long]$X, [long]$Y, [long]$Z)
	$data = @{}
	foreach ($b in $Specs) {
		$d = Get-SlipwayE2EBlockData -X ($X + $b.Dx) -Y ($Y + $b.Dy) -Z ($Z + $b.Dz)
		if ($d) { $data[$b.Name] = $d }
	}
	$data
}

function Complete-SlipwayE2EScenario {
	<# .SYNOPSIS Adds the log checks, writes the report, records validation.json and returns the result. #>
	param([Parameter(Mandatory)]$Run, [Parameter(Mandatory)]$Session, [long]$ServerOffset, [long]$ClientOffset, [string]$Milestone = '', [string[]]$AlsoIgnore = @(), $Client2, [long]$Client2Offset = 0)
	$serverProblems = @(Get-SlipwayE2ELogProblems -LogPath $Session.Server.LogPath -Offset $ServerOffset -AlsoIgnore $AlsoIgnore)
	Add-SlipwayE2ECheck -Run $Run -Title 'server log has no errors, exceptions or Slipway warnings' -Condition ($serverProblems.Count -eq 0) -Detail (($serverProblems | Select-Object -First 5) -join ' / ') | Out-Null
	$clientProblems = @(Get-SlipwayE2ELogProblems -LogPath $Session.Client.ClientLog -Offset $ClientOffset -AlsoIgnore $AlsoIgnore)
	Add-SlipwayE2ECheck -Run $Run -Title 'client log has no errors, exceptions or Slipway warnings' -Condition ($clientProblems.Count -eq 0) -Detail (($clientProblems | Select-Object -First 5) -join ' / ') | Out-Null
	if ($Client2) {
		$p2 = @(Get-SlipwayE2ELogProblems -LogPath $Client2.ClientLog -Offset $Client2Offset -AlsoIgnore $AlsoIgnore)
		Add-SlipwayE2ECheck -Run $Run -Title 'second client log has no errors, exceptions or Slipway warnings' -Condition ($p2.Count -eq 0) -Detail (($p2 | Select-Object -First 5) -join ' / ') | Out-Null
	}
	$serverExcerpt = Join-Path $Run.Directory 'server-log-excerpt.txt'
	Read-SlipwayE2ELogSince -LogPath $Session.Server.LogPath -Offset $ServerOffset | Set-Content $serverExcerpt -Encoding UTF8
	$clientExcerpt = Join-Path $Run.Directory 'client-log-excerpt.txt'
	Read-SlipwayE2ELogSince -LogPath $Session.Client.ClientLog -Offset $ClientOffset | Set-Content $clientExcerpt -Encoding UTF8
	$result = Save-SlipwayE2EReport -Run $Run
	Add-SlipwayValidation -Run $Run -Result $result -Milestone $Milestone | Out-Null
	Write-Host "Scenario $($Run.Name): $result -> $($Run.Directory)"
	$result
}

function Get-SlipwayE2ERider {
	<# .SYNOPSIS Parses the agent's 'slipway rider' answer. #>
	param([Parameter(Mandatory)]$Client)
	$text = Invoke-SlipwayE2EAgent -Client $Client -Verb slipway -Argument 'rider'
	$o = [ordered]@{ Text = $text }
	foreach ($m in [regex]::Matches($text, '(\w+)=([^\s]+)')) { $o[$m.Groups[1].Value] = $m.Groups[2].Value }
	if ($o.Contains('local')) { $o.Local = @($o.local -split ',' | ForEach-Object { [double]$_ }) } else { $o.Local = $null }
	$o.Carrier = [long]$o.carrier
	[pscustomobject]$o
}
