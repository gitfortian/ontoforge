$ErrorActionPreference = 'Stop'

# Provision a dedicated PostgreSQL login for the M5 source DataSource.
# Required environment: DB_URL, DB_USERNAME, DB_PASSWORD,
# ONTOFORGE_BINDING_SOURCE_PASSWORD. The login name is intentionally fixed.
$jdbcUrl = $env:DB_URL
if ([string]::IsNullOrWhiteSpace($jdbcUrl)) {
  $jdbcUrl = 'jdbc:postgresql://127.0.0.1:5432/ontoforge'
}
if (-not $jdbcUrl.StartsWith('jdbc:postgresql://')) {
  throw 'DB_URL must be a PostgreSQL JDBC URL.'
}
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
  throw 'DB_PASSWORD is required for administrative provisioning.'
}
if ([string]::IsNullOrWhiteSpace($env:ONTOFORGE_BINDING_SOURCE_PASSWORD)) {
  throw 'ONTOFORGE_BINDING_SOURCE_PASSWORD is required for the read-only source login.'
}
$psqlUrl = $jdbcUrl.Substring('jdbc:'.Length)
$env:PGPASSWORD = $env:DB_PASSWORD
$adminUser = $env:DB_USERNAME
if ([string]::IsNullOrWhiteSpace($adminUser)) { $adminUser = 'postgres' }
try {
  & psql $psqlUrl --username $adminUser --set ON_ERROR_STOP=1 --file scripts/provision-m5-source-reader.sql
  if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL source-reader provisioning failed.' }
} finally {
  Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
}
