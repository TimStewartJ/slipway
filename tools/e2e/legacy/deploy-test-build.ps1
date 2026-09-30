# LEGACY (retired with the Prism scenarios, see README.md). Builds Slipway (and the e2e agent), stops the running e2e session and installs the new jars into the e2e server
# and the Prism test instances. Usage: tools\e2e\legacy\deploy-test-build.ps1 [-SkipTests]
param([switch]$SkipTests, [switch]$SkipAgent)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\scenarios\_common.ps1')
$repo = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-25.0.3+9"
$tasks = @(if ($SkipTests) { 'jar' } else { 'jar'; 'test' })
& (Join-Path $repo 'gradlew.bat') -p $repo @tasks --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'Building Slipway failed' }
$agent = if ($SkipAgent) { (Get-ChildItem (Join-Path $repo 'tools\e2e\agent\build\libs') -Filter 'slipway-e2e-agent-*.jar' | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1).FullName } else { Install-SlipwayE2EAgent }
Stop-SlipwayE2ESession | Out-Null
$jar = (Get-ChildItem (Join-Path $repo 'build\libs') -Filter 'slipway-fabric-*.jar' | Where-Object { $_.Name -notmatch 'sources' } | Select-Object -First 1).FullName
Set-SlipwayE2EServerMods -SlipwayJar $jar | Out-Null
foreach ($instance in 'Slipway-Test-MC-26.3-Fabric', 'Slipway-Test2-MC-26.3-Fabric') {
	if (Test-Path (Join-Path (Get-SlipwayE2EInstanceDir $instance) 'instance.cfg')) {
		Set-SlipwayE2EInstanceMods -InstanceId $instance -SlipwayJar $jar -AgentJar $agent | Out-Null
	}
}
[pscustomobject]@{ Jar = $jar; Sha256 = (Get-FileHash $jar -Algorithm SHA256).Hash; Agent = $agent }
