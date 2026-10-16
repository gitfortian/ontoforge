<#
.SYNOPSIS
  OFIP-0003 real loopback-only snapshot acceptance using native Windows PostgreSQL.
.DESCRIPTION
  Exercise creates ONE uniquely named test Draft and TWO immutable Snapshots.
  It does not delete or update existing snapshots. Recheck verifies immutable
  content, digest and installation after restarting the backend without wiping PG.
.EXAMPLE
  .\scripts\verify-snapshot.ps1 -Mode Exercise -AllowCreateTestDraft
.EXAMPLE
  .\scripts\verify-snapshot.ps1 -Mode Recheck -EvidencePath 'C:\Temp\ontoforge-snapshot-acceptance-xxx.json'
#>
[CmdletBinding()]
param(
  [ValidateSet('Exercise','Recheck')][string]$Mode='Exercise',
  [switch]$AllowCreateTestDraft,
  [string]$EvidencePath='',
  [string]$ApiBase='http://127.0.0.1:8080',
  [string]$PackageId=''
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$address=$null
if (-not [Uri]::TryCreate($ApiBase,[UriKind]::Absolute,[ref]$address) -or
    $address.Scheme -ne 'http' -or
    $address.Host -notin @('127.0.0.1','localhost') -or
    -not [string]::IsNullOrEmpty($address.UserInfo) -or
    $address.AbsolutePath -notin @('','/') -or
    -not [string]::IsNullOrEmpty($address.Query) -or
    -not [string]::IsNullOrEmpty($address.Fragment)) {
  throw 'Only loopback HTTP root URLs are supported; never point S0 at production.'
}
$base=$ApiBase.TrimEnd('/')
function Must([bool]$ok,[string]$description) {
  if(-not $ok){throw "[FAIL] $description"}
}
function Call([string]$path,[string]$verb='GET',[object]$payload=$null) {
  $req=@{Uri=$base+$path;Method=$verb;TimeoutSec=20;ErrorAction='Stop'}
  if(-not [string]::IsNullOrWhiteSpace($env:ONTOFORGE_BEARER_TOKEN)) {
    $req.Headers=@{Authorization='Bearer '+$env:ONTOFORGE_BEARER_TOKEN}
  }
  if($null -ne $payload) {
    $req.ContentType='application/json; charset=utf-8'
    $req.Body=ConvertTo-Json -InputObject $payload -Depth 25 -Compress
  }
  return Invoke-RestMethod @req
}
function Post([string]$path,[object]$payload) {
  return Call $path 'POST' $payload
}
function Check409([string]$path,[object]$payload) {
  try {
    $null=Call $path 'POST' $payload
    throw '[FAIL] Stale or duplicate revision was accepted.'
  } catch {
    if($_.Exception.Message.StartsWith('[FAIL]')){throw}
    if($null -eq $_.Exception.Response){throw}
    Must ([int]$_.Exception.Response.StatusCode -eq 409) 'Expected HTTP 409'
  }
}
$system=Call '/api/system/status'
Must ($system.service -eq 'ontoforge' -and $system.database -eq 'CONNECTED') 'Actual Spring Boot + PostgreSQL required'
Must ($system.installationId -match '^[a-fA-F0-9-]{36}$') 'No real installation identity'
if($Mode -eq 'Recheck'){
  Must (-not [string]::IsNullOrWhiteSpace($EvidencePath)) 'Recheck requires saved -EvidencePath'
  Must (Test-Path -LiteralPath $EvidencePath -PathType Leaf) 'Evidence file not found'
  $record=Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json
  Must ($record.schemaVersion -eq 'S0-MVP-0.3-snapshot-acceptance-v1') 'Evidence profile unknown'
  Must ($record.installationId -eq $system.installationId) 'Installation UUID changed after restart'
  $draft=Call ('/api/model/drafts/'+$record.draftId)
  Must ($draft.packageId -eq $record.packageId -and [long]$draft.revision -eq 3) 'Expected existing revision 3 draft'
  foreach($i in @(0,1)){
    $id=$record.snapshots[$i].id
    Must ($id -match '^[a-fA-F0-9-]{36}$') 'Invalid snapshot identity'
    $s=Call ('/api/model/snapshots/'+$id)
    Must ($s.sourceDraftId -eq $record.draftId) 'Snapshot source changed'
    Must ($s.packageId -eq $record.packageId) 'Snapshot package changed'
    Must ($s.payloadSha256 -eq $record.snapshots[$i].sha256) 'Immutable content fingerprint changed'
    Must ($s.canonicalizationProfile -eq 'jcs-rfc8785-v1') 'Digest profile changed'
    Must ($s.lifecycle -eq 'LOCAL_SNAPSHOT_ONLY') 'Snapshot cannot become approved or published'
  }
  Write-Host '[PASS] After backend restart, V3 snapshots and SHA-256 digests match saved evidence.'
  Write-Host ('[PASS] installationId='+$system.installationId+' draftId='+$record.draftId)
  return
}
Must ($AllowCreateTestDraft.IsPresent) 'Explicit -AllowCreateTestDraft is required: this writes a persistent test draft and two snapshots.'
$token=[Guid]::NewGuid().ToString('N')
$package=if([string]::IsNullOrWhiteSpace($PackageId)){'acceptance.p'+$token}else{$PackageId}
$created=Post '/api/model/drafts' @{packageId=$package}
Must ($created.packageId -eq $package -and [long]$created.revision -eq 1) 'Draft creation failed'
$draftId=$created.draftId
$doc=@{
  editorSchemaVersion='0.1.0';linkTypes=@()
  objectTypes=@(@{
    id='production-batch';apiName='ProductionBatch';label='Production Batch'
    description='Before snapshot';primaryKeyRef='batch-id'
    properties=@(@{id='batch-id';apiName='batchId';valueType='STRING'
                   required=$true;searchable=$true;classification='INTERNAL'})
  })
}
$draft=Call ('/api/model/drafts/'+$draftId) 'PUT' @{expectedRevision=1;editingContent=$doc}
Must ([long]$draft.revision -eq 2) 'First WIP revision must be 2'
$report=Post ('/api/model/drafts/'+$draftId+'/validate') @{expectedRevision=2}
Must ($report.status -eq 'VALID' -and [long]$report.validatedRevision -eq 2) 'Revision 2 Native Candidate invalid'
$key1=[Guid]::NewGuid().ToString()
$first=Post ('/api/model/drafts/'+$draftId+'/snapshots') @{expectedRevision=2;idempotencyKey=$key1}
Must ($first.payloadSha256 -match '^[a-f0-9]{64}$') 'Expected real SHA-256'
Must ($first.lifecycle -eq 'LOCAL_SNAPSHOT_ONLY') 'Unexpected publication state'
Must ($first.canonicalizationProfile -eq 'jcs-rfc8785-v1') 'Expected RFC8785/JCS digest'
$replay=Post ('/api/model/drafts/'+$draftId+'/snapshots') @{expectedRevision=2;idempotencyKey=$key1}
Must ($replay.snapshotId -eq $first.snapshotId) 'Same key must return same snapshot'
Write-Host ('[PASS] revision 2 frozen with JCS/SHA-256 '+$first.snapshotId)
$doc.objectTypes[0].description='After snapshot'
$doc.objectTypes[0].properties[0].searchable=$false
$draft=Call ('/api/model/drafts/'+$draftId) 'PUT' @{expectedRevision=2;editingContent=$doc}
Must ([long]$draft.revision -eq 3 -and $draft.validation.status -eq 'NOT_RUN') 'Edit must invalidate previous VALID'
$report=Post ('/api/model/drafts/'+$draftId+'/validate') @{expectedRevision=3}
Must ($report.status -eq 'VALID') 'Revision 3 Native Candidate invalid'
$key2=[Guid]::NewGuid().ToString()
$second=Post ('/api/model/drafts/'+$draftId+'/snapshots') @{expectedRevision=3;idempotencyKey=$key2}
Must ($second.snapshotId -ne $first.snapshotId -and $second.payloadSha256 -ne $first.payloadSha256) 'Distinct version must have distinct content'
Check409 ('/api/model/drafts/'+$draftId+'/snapshots') @{expectedRevision=3;idempotencyKey=([Guid]::NewGuid().ToString())}
$diff=Call ('/api/model/snapshot-diffs?from='+$first.snapshotId+'&to='+$second.snapshotId)
Must ($diff.packageId -eq $package -and $diff.changes.Count -ge 2) 'Missing real structural diff'
Must (($diff.changes | Where-Object { $_.riskLevel -eq 'POTENTIALLY_BREAKING' }).Count -ge 1) 'Must flag searchable regression'
$reloaded=Call ('/api/model/snapshots/'+$first.snapshotId)
Must ($reloaded.payloadSha256 -eq $first.payloadSha256) 'Historical SHA changed'
Must ($reloaded.snapshotContent.objectTypes[0].description -eq 'Before snapshot') 'Old snapshot overwritten'
Write-Host '[PASS] Two immutable snapshots, structural diff, revision CAS 409 and original digest.'
if([string]::IsNullOrWhiteSpace($EvidencePath)){
  $EvidencePath=Join-Path ([IO.Path]::GetTempPath()) ('ontoforge-snapshot-acceptance-'+$token+'.json')
}
$record=@{
  schemaVersion='S0-MVP-0.3-snapshot-acceptance-v1'
  createdUtc=[DateTime]::UtcNow.ToString('o')
  installationId=$system.installationId
  packageId=$package;draftId=$draftId
  snapshots=@(
    @{id=$first.snapshotId;sha256=$first.payloadSha256;revision=2},
    @{id=$second.snapshotId;sha256=$second.payloadSha256;revision=3}
  )
  windowsRestartRecheck='NOT_RUN'
}
ConvertTo-Json -InputObject $record -Depth 15 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
Write-Host "[PASS] Exercise complete. Evidence: $EvidencePath"
Write-Host 'Now STOP and RESTART Spring Boot without resetting PostgreSQL; then run:'
Write-Host ('.\scripts\verify-snapshot.ps1 -Mode Recheck -EvidencePath "'+$EvidencePath+'"')
Write-Host '[NOTE] The isolated acceptance draft and both immutable snapshots remain in your test database.'
