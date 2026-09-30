# Leak isolation matrix: runs leak-new.ps1 with identical cycles in several mod configurations, several times each,
# and writes a summary (summary.md, summary.json) of heap after GC, live world objects at the title screen and
# per-cycle growth. Results go to E:\slipway-e2e\runs\leak-matrix-<stamp>\<config>-<world>-r<n>.
param(
	[string[]]$Configs = @('full', 'noDH', 'stackNoSlipway', 'sodiumIris', 'dhOnly', 'vanilla', 'slipwayOnly'),
	[ValidateSet('plain', 'vessels', 'auto')][string]$World = 'plain',
	[int]$Runs = 1,
	[int]$Cycles = 5,
	[string[]]$HeapDumpConfigs = @(),
	[switch]$Nmt,
	[string]$SlipwayJar,
	[string]$DhJar,
	[string]$MatrixDir
)
$ErrorActionPreference = 'Stop'
if (-not $MatrixDir) { $MatrixDir = Join-Path 'E:\slipway-e2e\runs' ("leak-matrix-" + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $MatrixDir | Out-Null
$summary = [System.Collections.Generic.List[object]]::new()
$summaryFile = Join-Path $MatrixDir 'summary.json'
if (Test-Path $summaryFile) { foreach ($s in (Get-Content $summaryFile -Raw | ConvertFrom-Json)) { $summary.Add($s) } }
for ($r = 1; $r -le $Runs; $r++) {
	foreach ($config in $Configs) {
		$worldKind = if ($World -eq 'auto') { if ($config -in 'full', 'full-bliss', 'noDH', 'noDH-bliss', 'slipwayOnly') { 'vessels' } else { 'plain' } } else { $World }
		$n = 1 + @($summary | Where-Object { $_.Config -eq $config -and $_.World -eq $worldKind }).Count
		$out = Join-Path $MatrixDir ("{0}-{1}-r{2}" -f $config, $worldKind, $n)
		Write-Host "=== $config / $worldKind / run $n ($(Get-Date -Format 'HH:mm:ss'))"
		$args = @{ Config = $config; World = $worldKind; Cycles = $Cycles; OutDir = $out }
		if ($SlipwayJar) { $args.SlipwayJar = $SlipwayJar }
		if ($DhJar) { $args.DhJar = $DhJar }
		if ($HeapDumpConfigs -contains $config) { $args.HeapDump = $true }
		if ($Nmt) { $args.Nmt = $true }
		$started = Get-Date
		$result = & (Join-Path $PSScriptRoot 'leak-new.ps1') @args *>&1 | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['Rows'] } | Select-Object -Last 1
		$rows = @($result.Rows)
		$entry = [ordered]@{ Config = $config; World = $worldKind; Run = $n; Result = $result.Result; Minutes = [Math]::Round(((Get-Date) - $started).TotalMinutes, 1); Directory = $out }
		if ($rows.Count -ge 2) {
			$entry.HeapMb = @($rows | ForEach-Object { $_.HeapUsedMb })
			$entry.HistogramMb = @($rows | ForEach-Object { $_.HistogramMb })
			$entry.HeapPerCycleMb = [Math]::Round(($rows[-1].HistogramMb - $rows[1].HistogramMb) / [Math]::Max(1, $rows.Count - 2), 1)
			foreach ($k in 'IntegratedServer', 'ServerLevel', 'ClientLevel', 'LevelChunk') { $entry[$k] = @($rows | ForEach-Object { $_.$k }) }
			$entry.PrivatePerCycleMb = [Math]::Round(($rows[-1].PrivateMb - $rows[1].PrivateMb) / [Math]::Max(1, $rows.Count - 2), 1)
			if ($rows[0].PSObject.Properties['NativeMb']) { $entry.NativePerCycleMb = [Math]::Round(($rows[-1].NativeMb - $rows[1].NativeMb) / [Math]::Max(1, $rows.Count - 2), 1) }
			if ($rows[0].PSObject.Properties['NmtJvmNonHeapMb']) {
				$entry.NmtJvmNonHeapPerCycleMb = [Math]::Round(($rows[-1].NmtJvmNonHeapMb - $rows[1].NmtJvmNonHeapMb) / [Math]::Max(1, $rows.Count - 2), 1)
				$entry.NmtOutsideJvmPerCycleMb = [Math]::Round(($rows[-1].NmtOutsideJvmMb - $rows[1].NmtOutsideJvmMb) / [Math]::Max(1, $rows.Count - 2), 1)
				$entry.Threads = @($rows | ForEach-Object { $_.Threads })
			}
		}
		$summary.Add([pscustomobject]$entry)
		$summary | ConvertTo-Json -Depth 5 | Set-Content $summaryFile
		Write-Host ("    -> {0} in {1} min; histogram MB {2}; server levels {3}; client levels {4}; private MB/cycle {5}; NMT JVM/outside MB/cycle {6}/{7}; threads {8}" -f $entry.Result, $entry.Minutes, ($entry.HistogramMb -join ' '), ($entry.ServerLevel -join ' '), ($entry.ClientLevel -join ' '), $entry.PrivatePerCycleMb, $entry.NmtJvmNonHeapPerCycleMb, $entry.NmtOutsideJvmPerCycleMb, ($entry.Threads -join ' '))
	}
}
$md = [System.Collections.Generic.List[string]]::new()
$md.Add('# Leak isolation matrix'); $md.Add('')
$md.Add("Cycles per run: $Cycles. Live objects counted at the title screen after each close, after a full GC (jcmd GC.class_histogram).")
$md.Add(''); $md.Add('| Config | World | Run | Result | Histogram MB per cycle | MB/cycle (2..N) | IntegratedServer | ServerLevel | ClientLevel | LevelChunk | Private MB/cycle | Native MB/cycle |')
$md.Add('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |')
foreach ($e in $summary) {
	$md.Add("| $($e.Config) | $($e.World) | $($e.Run) | $($e.Result) | $(($e.HistogramMb | ForEach-Object { '{0:N0}' -f $_ }) -join ' ') | $($e.HeapPerCycleMb) | $($e.IntegratedServer -join ' ') | $($e.ServerLevel -join ' ') | $($e.ClientLevel -join ' ') | $($e.LevelChunk -join ' ') | $($e.PrivatePerCycleMb) | $($e.NativePerCycleMb) |")
}
$md | Set-Content (Join-Path $MatrixDir 'summary.md') -Encoding UTF8
$MatrixDir
