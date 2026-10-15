[CmdletBinding()]
param(
    [string]$PsqlPath = 'psql',
    [string]$ExpectedInstallationId = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# S0 intentionally validates a developer's local PostgreSQL + running API.
# It never creates, drops, migrates, or modifies the database.
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
    throw 'DB_PASSWORD is required in this PowerShell session. Set it before running verification.'
}

$jdbcUrl = if ([string]::IsNullOrWhiteSpace($env:DB_URL)) {
    'jdbc:postgresql://127.0.0.1:5432/ontoforge'
} else {
    $env:DB_URL
}
$parsed = [regex]::Match($jdbcUrl, '^jdbc:postgresql://(?<host>[^/:?]+)(?::(?<port>[0-9]+))?/(?<database>[A-Za-z0-9_-]+)$')
if (-not $parsed.Success) {
    throw 'This S0 smoke script supports simple local JDBC URLs such as jdbc:postgresql://127.0.0.1:5432/ontoforge.'
}
$pgHost = $parsed.Groups['host'].Value
if ($pgHost -notin @('127.0.0.1', 'localhost')) {
    throw 'S0 local verification refuses non-loopback PostgreSQL hosts.'
}
$pgPort = if ($parsed.Groups['port'].Success) { $parsed.Groups['port'].Value } else { '5432' }
$pgDatabase = $parsed.Groups['database'].Value
$pgUser = if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME)) { 'postgres' } else { $env:DB_USERNAME }

if ($PsqlPath -eq 'psql' -and -not (Get-Command psql -ErrorAction SilentlyContinue)) {
    throw 'psql was not found in PATH. Pass -PsqlPath "C:\Program Files\PostgreSQL\<version>\bin\psql.exe".'
}
if ($PsqlPath -ne 'psql' -and -not (Test-Path -LiteralPath $PsqlPath -PathType Leaf)) {
    throw "psql executable not found at: $PsqlPath"
}

$sql = "SELECT (SELECT count(*)::text FROM flyway_schema_history WHERE version='1' AND success) || '|' || (SELECT installation_id::text FROM platform_installation WHERE id=1);"
$previousPgPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $env:DB_PASSWORD, 'Process')
    $rawResult = @(& $PsqlPath -X -A -t -h $pgHost -p $pgPort -U $pgUser -d $pgDatabase -v ON_ERROR_STOP=1 -c $sql)
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed (exit code $LASTEXITCODE). Check PostgreSQL service, credentials and database."
    }
} finally {
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $previousPgPassword, 'Process')
}

$dbResult = [regex]::Match(($rawResult -join "`n").Trim(), '^1\|([0-9a-fA-F-]{36})$')
if (-not $dbResult.Success) {
    throw 'Expected successful Flyway V1 and one persisted platform_installation UUID.'
}
$databaseInstallationId = $dbResult.Groups[1].Value

$apiPort = if ([string]::IsNullOrWhiteSpace($env:PORT)) { '8080' } else { $env:PORT }
if ($apiPort -notmatch '^[0-9]+$') {
    throw 'PORT must be a local numeric port.'
}
$apiUrl = "http://127.0.0.1:$apiPort/api/system/status"
try {
    $status = Invoke-RestMethod -Uri $apiUrl -Method Get -TimeoutSec 10
} catch {
    throw "Cannot reach $apiUrl. Start the Spring Boot backend before running this check. $($_.Exception.Message)"
}
if ($status.service -ne 'ontoforge' -or $status.version -ne 'S0-MVP-0.1' -or $status.database -ne 'CONNECTED') {
    throw 'The status API did not report the expected real S0-MVP-0.1 service and PostgreSQL connection.'
}
if ($status.installationId -ne $databaseInstallationId) {
    throw "API installationId does not match the PostgreSQL row. Check that both target the same database."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedInstallationId) -and $databaseInstallationId -ne $ExpectedInstallationId) {
    throw 'Installation ID changed after restart. Investigate database switching or data loss.'
}

Write-Host "[OK] PostgreSQL: $pgHost`:$pgPort/$pgDatabase (Flyway V1 applied)"
Write-Host "[OK] API: $apiUrl"
Write-Host "[OK] Installation ID: $databaseInstallationId"
Write-Host 'To verify persistence: restart Spring Boot, then run this script with -ExpectedInstallationId <UUID>.'
