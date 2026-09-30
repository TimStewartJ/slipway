# Helpers for heap-dump analysis with Eclipse Memory Analyzer (MAT) in batch mode.
#   . tools\e2e\mat.ps1
#   Invoke-SlipwayMat -Dump <e2e root>\heap\x.hprof -Command 'oql "SELECT s FROM net.minecraft.client.server.IntegratedServer s"'
# MAT is unpacked in <e2e root>\tools\mat (not in the repository); the e2e root is $env:SLIPWAY_E2E_ROOT, default
# E:\slipway-e2e. The first query on a dump builds MAT's index files next to it (about a minute for 1.5 GB); later
# queries reuse them.

$script:MatDir = Join-Path $(if ($env:SLIPWAY_E2E_ROOT) { $env:SLIPWAY_E2E_ROOT } else { 'E:\slipway-e2e' }) 'tools\mat\mat'

function Invoke-SlipwayMat {
	<# .SYNOPSIS Runs one MAT query command on a heap dump and returns its text output (all result pages). #>
	param([Parameter(Mandatory)][string]$Dump, [Parameter(Mandatory)][string]$Command, [int]$TimeoutSeconds = 1800)
	$exe = Join-Path $script:MatDir 'MemoryAnalyzerc.exe'
	$ini = Join-Path $script:MatDir 'MemoryAnalyzer.ini'
	$base = [IO.Path]::Combine([IO.Path]::GetDirectoryName($Dump), [IO.Path]::GetFileNameWithoutExtension($Dump))
	Remove-Item "${base}_Query" -Recurse -Force -ErrorAction SilentlyContinue
	Remove-Item "${base}_Query.zip" -Force -ErrorAction SilentlyContinue
	$log = "${base}_mat.log"
	& $exe --launcher.ini $ini -consoleLog -nosplash -application org.eclipse.mat.api.parse $Dump "-command=$Command" '-format=txt' '-unzip' 'org.eclipse.mat.api:query' *> $log
	$pages = Get-ChildItem "${base}_Query\pages" -Filter *.txt -ErrorAction SilentlyContinue | Sort-Object Name
	if (-not $pages) { throw "MAT produced no output for '$Command' (see $log)" }
	($pages | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
}
