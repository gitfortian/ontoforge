param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repositoryRoot
try {
    if (-not $SkipBuild) {
        & mvn -B -pl ontoforge-boot -am -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Maven reactor build failed.' }
    }
    $projectVersion = ([xml](Get-Content -Raw -LiteralPath 'pom.xml')).project.version
    $bootJar = Join-Path $repositoryRoot "ontoforge-boot/target/ontoforge-boot-$projectVersion.jar"
    if (-not (Test-Path -LiteralPath $bootJar -PathType Leaf)) {
        throw 'Boot jar is missing. Run this script without -SkipBuild first.'
    }
    # Uses the caller's DB_*, Spring profile, OIDC and optional source settings.
    & java -jar $bootJar
    if ($LASTEXITCODE -ne 0) { throw 'OntoForge exited with an error.' }
}
finally {
    Pop-Location
}
