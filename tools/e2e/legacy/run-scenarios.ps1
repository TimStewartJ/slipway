# LEGACY (retired with the Prism scenarios, see README.md). Runs end-to-end scenarios one after another and prints a summary line per scenario. Scenarios that need shaders
# restart the session with Iris on, so list shader-off scenarios first to avoid needless restarts.
param(
	[string[]]$Scenarios = @('assemble-mixed', 'flight-rotation', 'deck-walk', 'interaction', 'collision', 'forged-packets', 'save-reload',
		'multiplayer', 'render-iris', 'perf', 'leak'),
	[switch]$StopAfter
)
$ErrorActionPreference = 'Continue'
$results = [System.Collections.Generic.List[object]]::new()
foreach ($name in $Scenarios) {
	$script = Join-Path $PSScriptRoot "scenarios\$name.ps1"
	$started = Get-Date
	Write-Host "=== $name ($(Get-Date -Format 'HH:mm:ss'))"
	$output = & $script 2>&1
	$verdict = [string]($output | Where-Object { $_ -is [string] -and $_ -match '^(pass|fail)$' } | Select-Object -Last 1)
	$runLine = [string]($output | Where-Object { "$_" -match '^Scenario .* -> ' } | Select-Object -Last 1)
	$failed = @($output | Where-Object { "$_" -match '^\[FAIL\]' } | ForEach-Object { "$_" })
	$results.Add([pscustomobject]@{ Scenario = $name; Result = $(if ($verdict) { $verdict } else { 'error' }); Minutes = [Math]::Round(((Get-Date) - $started).TotalMinutes, 1)
		Run = ($runLine -replace '^.* -> ', ''); Failures = ($failed -join ' | ') })
	$failed | ForEach-Object { Write-Host "   $_" }
	Write-Host "    -> $($results[-1].Result) in $($results[-1].Minutes) min: $($results[-1].Run)"
}
if ($StopAfter) {
	. (Join-Path $PSScriptRoot '..\scenarios\_common.ps1')
	Stop-SlipwayE2ESession | Out-Null
}
$results | Format-Table Scenario, Result, Minutes, Run -AutoSize | Out-String -Width 220
