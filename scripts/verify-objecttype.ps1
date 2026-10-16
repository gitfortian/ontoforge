<#
.SYNOPSIS
  S0-MVP-0.2 acceptance on native Windows PostgreSQL through REAL loopback API.
.DESCRIPTION
  Exercise creates ONE uniquely named test draft and performs create/WIP/validate/
  re-open/CAS conflict. It never deletes or changes existing drafts. The created
  acceptance draft remains in the DB (no delete API exists). Recheck reads
  the recorded draft after Spring Boot has been restarted.
  Only connect to a localhost HTTP API. Never send DB credentials or passwords.
.EXAMPLE
  .\scripts\verify-objecttype.ps1 -Mode Exercise -AllowCreateTestDraft
.EXAMPLE
  .\scripts\verify-objecttype.ps1 -Mode Recheck -EvidencePath "C:\Temp\ontoforge-acceptance-xxxx.json"
#>
[CmdletBinding()]
param(
    [ValidateSet('Exercise', 'Recheck')]
    [string]$Mode = 'Exercise',
    [switch]$AllowCreateTestDraft,
    [string]$EvidencePath = '',
    [string]$ApiBase = 'http://127.0.0.1:8080',
    [string]$PackageId = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Absolutely refuse calls to remote, non-HTTP, userinfo, or nested API origins.
$uri = $null
if (-not [Uri]::TryCreate($ApiBase, [UriKind]::Absolute, [ref]$uri) -or
    $uri.Scheme -ne 'http' -or
    $uri.Host -notin @('localhost', '127.0.0.1') -or
    -not [string]::IsNullOrEmpty($uri.UserInfo) -or
    $uri.AbsolutePath -notin @('', '/') -or
    -not [string]::IsNullOrEmpty($uri.Query) -or
    -not [string]::IsNullOrEmpty($uri.Fragment)) {
    throw 'ApiBase must be the root of a local HTTP URL, e.g. http://127.0.0.1:8080. Remote systems are forbidden.'
}
$api = $ApiBase.TrimEnd('/') + '/api/model/drafts'

function Assert-Condition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "[FAIL] $Message" }
}

function Invoke-LocalJson {
    param([string]$Path, [string]$Method = 'GET', [object]$Body = $null)
    $params = @{
        Uri = $ApiBase.TrimEnd('/') + $Path
        Method = $Method
        TimeoutSec = 20
        ErrorAction = 'Stop'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ONTOFORGE_BEARER_TOKEN)) {
        $params.Headers = @{Authorization = 'Bearer ' + $env:ONTOFORGE_BEARER_TOKEN}
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = ConvertTo-Json -InputObject $Body -Depth 25 -Compress
    }
    return Invoke-RestMethod @params
}

function Assert-ApiError {
    param([string]$Path, [string]$Method, [object]$Body, [int]$ExpectedStatus, [string]$ExpectedCode)
    try {
        $null = Invoke-LocalJson -Path $Path -Method $Method -Body $Body
        throw "[FAIL] Expected HTTP $ExpectedStatus $ExpectedCode, request unexpectedly succeeded."
    } catch {
        if ($_.Exception.Message.StartsWith('[FAIL]')) { throw }
        if ($null -eq $_.Exception.Response) { throw }
        $actualStatus = [int]$_.Exception.Response.StatusCode
        Assert-Condition ($actualStatus -eq $ExpectedStatus) "Expected HTTP $ExpectedStatus but received $actualStatus."
        # The code is part of the stable contract. Read it from the response when
        # accessible on both Windows PowerShell 5.1 and PowerShell 7.
        $payload = $null
        if ($null -ne $_.ErrorDetails -and -not [string]::IsNullOrWhiteSpace($_.ErrorDetails.Message)) {
            try { $payload = ConvertFrom-Json -InputObject $_.ErrorDetails.Message -ErrorAction Stop } catch { }
        }
        if ($null -ne $payload) {
            Assert-Condition ($payload.code -eq $ExpectedCode) "Expected API code $ExpectedCode but received $($payload.code)."
        } else {
            # Windows PowerShell 5.1 sometimes omits ErrorDetails; safely read
            # the same HTTP error stream without disclosing internal trace.
            $stream = $_.Exception.Response.GetResponseStream()
            if ($null -ne $stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                try { $payload = ConvertFrom-Json -InputObject $reader.ReadToEnd() }
                finally { $reader.Dispose() }
                Assert-Condition ($payload.code -eq $ExpectedCode) "Expected API code $ExpectedCode but received $($payload.code)."
            } else { throw "[FAIL] Could not confirm API error code $ExpectedCode." }
        }
    }
}

$status = Invoke-LocalJson -Path '/api/system/status'
Assert-Condition ($status.service -eq 'ontoforge' -and $status.version -in @('S0-MVP-0.2','S0-MVP-0.3')) 'Unexpected OntoForge API/version.'
Assert-Condition ($status.database -eq 'CONNECTED' -and $status.modelModule -eq 'DRAFT_AVAILABLE') 'A REAL connected PostgreSQL Draft service is required.'
Assert-Condition ($status.authModule -eq 'JWT_PACKAGE_AUTHORIZATION') 'Expected B0 JWT package authorization.'
Assert-Condition ($status.installationId -match '^[0-9a-fA-F-]{36}$') 'Missing installation ID.'

if ($Mode -eq 'Recheck') {
    Assert-Condition (-not [string]::IsNullOrWhiteSpace($EvidencePath)) 'Recheck requires -EvidencePath from the previous Exercise.'
    Assert-Condition (Test-Path -LiteralPath $EvidencePath -PathType Leaf) 'Evidence file not found.'
    $evidence = Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json
    Assert-Condition ($evidence.schemaVersion -eq 'S0-MVP-0.2-acceptance-v1') 'Unknown evidence format.'
    Assert-Condition ($evidence.installationId -eq $status.installationId) 'Installation ID changed across restart.'
    Assert-Condition ($evidence.draftId -match '^[0-9a-fA-F-]{36}$') 'Invalid draftId in evidence.'
    $existing = Invoke-LocalJson -Path ("/api/model/drafts/" + $evidence.draftId)
    Assert-Condition ($existing.draftId -eq $evidence.draftId -and $existing.packageId -eq $evidence.packageId) 'Persisted draft identity changed.'
    Assert-Condition ([long]$existing.revision -eq [long]$evidence.revision) 'Draft revision changed since acceptance. Check competing editors.'
    Assert-Condition ($existing.editingContent.objectTypes[0].description -eq $evidence.description) 'Persisted ObjectType description changed or disappeared.'
    Assert-Condition ($existing.validation.status -eq 'NOT_RUN') 'Current revision should be NOT_RUN after final edit.'
    Write-Host "[PASS] Recheck after restart: installationId=$($status.installationId) draftId=$($evidence.draftId) revision=$($existing.revision)."
    Write-Host '[PASS] Exact same ObjectType and description came from the real database.'
    return
}

if (-not $AllowCreateTestDraft) {
    throw 'Exercise creates a permanent, uniquely named acceptance draft. Explicitly pass -AllowCreateTestDraft, preferably against your development/test database.'
}

$token = [Guid]::NewGuid().ToString('N')
if ([string]::IsNullOrWhiteSpace($PackageId)) { $PackageId = 'acceptance.p' + $token }
$created = Invoke-LocalJson -Path '/api/model/drafts' -Method 'POST' -Body @{packageId = $packageId}
Assert-Condition ($created.packageId -eq $packageId -and [long]$created.revision -eq 1) 'Create draft failed.'
$draftId = $created.draftId
$path = '/api/model/drafts/' + $draftId
Write-Host "[PASS] Created isolated test draft: $packageId ($draftId)."

$working = @{
    editorSchemaVersion = '0.1.0'
    objectTypes = @(@{
        id = 'production-batch'
        apiName = 'ProductionBatch'
        label = 'Production Batch'
        description = 'Acceptance draft'
        primaryKeyRef = $null
        properties = @()
    })
    linkTypes = @()
}
$wip = Invoke-LocalJson -Path $path -Method 'PUT' -Body @{expectedRevision=1;editingContent=$working}
Assert-Condition ([long]$wip.revision -eq 2 -and $wip.validation.status -eq 'NOT_RUN') 'WIP save must succeed without PK and invalidate checks.'
$loaded = Invoke-LocalJson -Path $path
Assert-Condition ($loaded.editingContent.objectTypes[0].apiName -eq 'ProductionBatch') 'WIP content was not persisted.'
$incomplete = Invoke-LocalJson -Path ($path+'/validate') -Method 'POST' -Body @{expectedRevision=2}
Assert-Condition ($incomplete.status -eq 'INCOMPLETE') 'Missing PK/property must produce INCOMPLETE, not VALID.'
Write-Host '[PASS] WIP save/reload and incomplete validation on revision 2.'

$working.objectTypes[0].primaryKeyRef = 'batch-id'
$working.objectTypes[0].properties = @(@{
    id = 'batch-id'
    apiName = 'batchId'
    label = 'Batch ID'
    valueType = 'STRING'
    required = $true
    searchable = $true
    classification = 'INTERNAL'
    enumValues = @()
})
$saved = Invoke-LocalJson -Path $path -Method 'PUT' -Body @{expectedRevision=2;editingContent=$working}
Assert-Condition ([long]$saved.revision -eq 3 -and $saved.validation.status -eq 'NOT_RUN') 'Revision 3 save failed.'
$valid = Invoke-LocalJson -Path ($path+'/validate') -Method 'POST' -Body @{expectedRevision=3}
Assert-Condition ($valid.status -eq 'VALID' -and [long]$valid.validatedRevision -eq 3) 'Native V0.2 candidate must validate as VALID at revision 3.'
Assert-Condition ((Invoke-LocalJson -Path $path).validation.status -eq 'VALID') 'Validation report must persist.'
Write-Host '[PASS] Native candidate VALID, report persisted at revision 3.'

$description = 'Accepted test description ' + $token
$working.objectTypes[0].description = $description
$changed = Invoke-LocalJson -Path $path -Method 'PUT' -Body @{expectedRevision=3;editingContent=$working}
Assert-Condition ([long]$changed.revision -eq 4 -and $changed.validation.status -eq 'NOT_RUN') 'Editing must increment revision and invalidate stale VALID.'
Assert-ApiError -Path $path -Method 'PUT' -Body @{expectedRevision=3;editingContent=$working} -ExpectedStatus 409 -ExpectedCode 'DRAFT_REVISION_CONFLICT'
$last = Invoke-LocalJson -Path $path
Assert-Condition ([long]$last.revision -eq 4 -and $last.editingContent.objectTypes[0].description -eq $description) 'Old revision overwrite was not rejected safely.'
Write-Host '[PASS] Revision CAS 409 and unchanged persisted content.'

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path ([IO.Path]::GetTempPath()) ("ontoforge-acceptance-$token.json")
}
$dir = Split-Path -Path $EvidencePath -Parent
Assert-Condition ([string]::IsNullOrWhiteSpace($dir) -or (Test-Path -LiteralPath $dir -PathType Container)) 'Evidence directory does not exist.'
$evidenceObject = @{
    schemaVersion = 'S0-MVP-0.2-acceptance-v1'
    checkedAtUtc = [DateTime]::UtcNow.ToString('o')
    apiBase = $ApiBase
    installationId = $status.installationId
    packageId = $packageId
    draftId = $draftId
    revision = 4
    description = $description
    windowsRestartRecheck = 'NOT_RUN'
}
ConvertTo-Json -InputObject $evidenceObject -Depth 12 |
    Set-Content -LiteralPath $EvidencePath -Encoding UTF8
Write-Host "[PASS] Exercise complete. Evidence: $EvidencePath"
Write-Host 'RESTART Spring Boot (leave PostgreSQL/data unchanged), then run:'
Write-Host ('.\scripts\verify-objecttype.ps1 -Mode Recheck -EvidencePath "' + $EvidencePath + '"')
Write-Host '[NOTE] The isolated test draft remains in the development database. This tool has no delete operation.'
