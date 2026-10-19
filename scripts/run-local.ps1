param([switch]$SkipBuild, [switch]$NonInteractive, [switch]$Reconfigure, [string]$PsqlPath = '')

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$localEnvironmentBefore = @{}
foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'ONTOFORGE_SECURITY_AUTHENTICATION_MODE',
        'ONTOFORGE_OBJECTSET_CURSOR_SIGNING_SECRET', 'ONTOFORGE_BOOTSTRAP_ADMIN_ENABLED',
        'ONTOFORGE_BOOTSTRAP_ADMIN_USERNAME', 'ONTOFORGE_BOOTSTRAP_ADMIN_PASSWORD',
        'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD')) {
    $item = Get-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue
    $localEnvironmentBefore[$name] = @{Present=($null -ne $item); Value=if ($item) { $item.Value } else { $null }}
}
Push-Location $repositoryRoot
try {
    . (Join-Path $PSScriptRoot 'local-environment.ps1')
    $setup = Initialize-LocalEnvironment $repositoryRoot $PsqlPath ([bool]$NonInteractive) ([bool]$Reconfigure)
    if (-not $SkipBuild) {
        & mvn -B -pl ontoforge-boot -am -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven reactor build failed.' }
    }
    $projectVersion = ([xml](Get-Content -Raw -LiteralPath 'pom.xml')).project.version
    $bootJar = Join-Path $repositoryRoot "ontoforge-boot/target/ontoforge-boot-$projectVersion.jar"
    if (-not (Test-Path -LiteralPath $bootJar -PathType Leaf)) {
        throw 'Boot jar is missing. Run this script without -SkipBuild first.'
    }
    if (-not $setup -or -not $setup.BootstrapPending) {
        & java -jar $bootJar
        if ($LASTEXITCODE -ne 0) { throw 'OntoForge exited with an error.' }
    } else {
        # Keep console output visible while removing the one-time bootstrap password
        # as soon as the real server and stored account are ready.
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = (Get-Command java).Source
        $start.Arguments = '-jar "' + $bootJar + '"'
        $start.UseShellExecute = $false
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $start
        try {
            [void]$process.Start()
            $apiPort = if ($env:PORT) { $env:PORT } else { '8080' }
            while (-not $process.WaitForExit(1000)) {
                if (-not $setup.BootstrapPending) { continue }
                $status = $null
                try {
                    $status = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/api/system/status" -TimeoutSec 2
                } catch { # The HTTP server may not yet be listening.
                }
                if ($status -and $status.database -eq 'CONNECTED') { [void](Complete-LocalBootstrap $setup) }
            }
            if ($process.ExitCode -ne 0) { throw 'OntoForge exited with an error; saved local setup is preserved for retry.' }
        } finally {
            if (-not $process.HasExited) { $process.Kill() }
            $process.Dispose()
        }
    }
}
finally {
    foreach ($name in $localEnvironmentBefore.Keys) {
        $before = $localEnvironmentBefore[$name]
        if ($before.Present) { [Environment]::SetEnvironmentVariable($name, $before.Value, 'Process') }
        else { Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue }
    }
    Pop-Location
}
