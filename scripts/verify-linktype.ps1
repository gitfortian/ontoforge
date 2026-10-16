<#
.SYNOPSIS
  OFIP-0004 real local LinkType acceptance: three ObjectTypes, two LinkTypes,
  immutable snapshots, structural risks and post-restart reconciliation.
.DESCRIPTION
  Run Exercise against an isolated native Windows 11 PostgreSQL test database.
  This creates ONE persistent uniquely named draft and TWO immutable snapshots,
  never deleting or changing other records. Stop/restart Spring Boot without
  resetting PostgreSQL, then run Recheck against the saved evidence file.
  A CI invocation on Linux PowerShell tests the API contract but does not
  qualify as real Windows PostgreSQL restart acceptance.
.EXAMPLE
  .\scripts\verify-linktype.ps1 -Mode Exercise -AllowCreateTestDraft
.EXAMPLE
  .\scripts\verify-linktype.ps1 -Mode Recheck -EvidencePath 'C:\Temp\ontoforge-linktype-acceptance-xxx.json'
#>
[CmdletBinding()]
param(
  [ValidateSet('Exercise','Recheck')][string]$Mode='Exercise',
  [switch]$AllowCreateTestDraft,
  [string]$EvidencePath='',
  [string]$ApiBase='http://127.0.0.1:8080'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$uri=$null
if(-not [Uri]::TryCreate($ApiBase,[UriKind]::Absolute,[ref]$uri) -or
   $uri.Scheme -ne 'http' -or
   $uri.Host -notin @('localhost','127.0.0.1') -or
   -not [string]::IsNullOrEmpty($uri.UserInfo) -or
   $uri.AbsolutePath -notin @('','/') -or
   -not [string]::IsNullOrEmpty($uri.Query) -or
   -not [string]::IsNullOrEmpty($uri.Fragment)){
  throw 'Only root loopback HTTP URLs are supported, never production environments.'
}
$base=$ApiBase.TrimEnd('/')
function Must([bool]$ok,[string]$detail){
  if(-not $ok){throw "[FAIL] $detail"}
}
function Call([string]$path,[string]$verb='GET',[object]$data=$null){
  $arg=@{Uri=$base+$path;Method=$verb;TimeoutSec=20;ErrorAction='Stop'}
  if($null -ne $data){
    $arg.ContentType='application/json; charset=utf-8'
    $arg.Body=ConvertTo-Json -InputObject $data -Depth 30 -Compress
  }
  return Invoke-RestMethod @arg
}
function Post([string]$path,[object]$data){return Call $path 'POST' $data}
function Link([string]$id,[string]$api,[string]$source,[string]$target,[string]$card,[bool]$evidence){
  return @{id=$id;apiName=$api;sourceTypeRef=$source;targetTypeRef=$target;cardinality=$card;evidenceRequired=$evidence}
}
function Type([string]$id,[string]$api){
  return @{id=$id;apiName=$api;label=$api;primaryKeyRef='business-id';properties=@(
    @{id='business-id';apiName='businessId';valueType='STRING';required=$true;searchable=$true;classification='INTERNAL'}
  )}
}
function RequireHttp409([string]$path,[object]$body){
  try {
    $null=Post $path $body
    throw '[FAIL] Stale/duplicate request unexpectedly succeeded'
  } catch {
    if($_.Exception.Message.StartsWith('[FAIL]')){throw}
    if($null -eq $_.Exception.Response){throw}
    Must ([int]$_.Exception.Response.StatusCode -eq 409) 'Expected HTTP 409'
  }
}
$status=Call '/api/system/status'
Must ($status.service -eq 'ontoforge' -and $status.database -eq 'CONNECTED') 'Actual local Spring Boot and PostgreSQL must be running'
Must ($status.snapshotModule -eq 'LOCAL_SNAPSHOT_AVAILABLE') 'S0-0.3 local Snapshot dependency is missing'
Must ($status.installationId -match '^[a-fA-F0-9-]{36}$') 'Installation ID missing'

if($Mode -eq 'Recheck'){
  Must (-not [string]::IsNullOrWhiteSpace($EvidencePath)) 'Recheck requires -EvidencePath from Exercise'
  Must (Test-Path -LiteralPath $EvidencePath -PathType Leaf) 'Evidence file does not exist'
  $record=Get-Content -LiteralPath $EvidencePath -Raw | ConvertFrom-Json
  Must ($record.schemaVersion -eq 'S0-MVP-0.4-linktype-acceptance-v1') 'Wrong evidence profile'
  Must ($record.installationId -eq $status.installationId) 'Installation identity changed after restart'
  $draft=Call ('/api/model/drafts/'+$record.draftId)
  Must ($draft.packageId -eq $record.packageId -and [long]$draft.revision -eq 3) 'Draft ID/package/revision changed'
  Must (@($draft.editingContent.objectTypes).Count -eq 3 -and @($draft.editingContent.linkTypes).Count -eq 2) 'Real draft lost object types or links'
  Must ($draft.editingContent.linkTypes[0].cardinality -eq 'ONE_TO_ONE') 'Revised link cardinality disappeared'
  Must ($draft.editingContent.linkTypes[0].evidenceRequired -eq $false) 'Revised evidence policy disappeared'
  foreach($i in @(0,1)){
    $old=$record.snapshots[$i]
    $s=Call ('/api/model/snapshots/'+$old.id)
    Must ($s.packageId -eq $record.packageId -and $s.sourceDraftId -eq $record.draftId) 'Snapshot identity changed'
    Must ($s.payloadSha256 -eq $old.sha256) 'Historical SHA-256 changed'
    Must ($s.canonicalizationProfile -eq 'jcs-rfc8785-v1') 'JCS profile changed'
    Must ($s.lifecycle -eq 'LOCAL_SNAPSHOT_ONLY') 'Snapshot impersonates production release'
    Must (@($s.snapshotContent.objectTypes).Count -eq 3) 'Historical objects missing'
    Must (@($s.snapshotContent.linkTypes).Count -eq 2) 'Historical relations missing'
  }
  $original=Call ('/api/model/snapshots/'+$record.snapshots[0].id)
  Must ($original.snapshotContent.linkTypes[0].cardinality -eq 'MANY_TO_ONE') 'Original historical cardinality overwritten'
  Must ($original.snapshotContent.linkTypes[0].evidenceRequired -eq $true) 'Original evidence requirement overwritten'
  $changes=Call ('/api/model/snapshot-diffs?from='+$record.snapshots[0].id+'&to='+$record.snapshots[1].id)
  $risk=@($changes.changes)
  Must (@($risk|Where-Object {$_.entityKind -eq 'LINK_TYPE' -and $_.riskLevel -eq 'POTENTIALLY_BREAKING'}).Count -ge 1) 'Cardinality risk missing'
  Must (@($risk|Where-Object {$_.reasonCode -eq 'LINK_EVIDENCE_RELAXED' -and $_.riskLevel -eq 'POLICY_REVIEW_REQUIRED'}).Count -eq 1) 'Evidence relaxation risk missing'
  Write-Host '[PASS] Reloaded 3 objects + 2 links and 2 immutable snapshots after service restart.'
  Write-Host ('[PASS] installationId='+$status.installationId+' draftId='+$record.draftId)
  return
}
Must ($AllowCreateTestDraft.IsPresent) 'Exercise requires -AllowCreateTestDraft; writes a persistent isolated draft and two snapshots'
$token=[Guid]::NewGuid().ToString('N')
$package='acceptance.p'+$token
$new=Post '/api/model/drafts' @{packageId=$package}
Must ($new.packageId -eq $package -and [long]$new.revision -eq 1) 'Draft create failed'
$id=$new.draftId
$doc=@{
  editorSchemaVersion='0.1.0'
  objectTypes=@((Type 'production-batch' 'ProductionBatch'),(Type 'work-order' 'WorkOrder'),(Type 'customer-order' 'CustomerOrder'))
  linkTypes=@(
    (Link 'batch-to-workorder' 'BatchToWorkOrder' 'production-batch' 'work-order' 'MANY_TO_ONE' $true),
    (Link 'workorder-to-order' 'WorkOrderToOrder' 'work-order' 'customer-order' 'MANY_TO_MANY' $true)
  )
}
$save=Call ('/api/model/drafts/'+$id) 'PUT' @{expectedRevision=1;editingContent=$doc}
Must ([long]$save.revision -eq 2 -and @($save.editingContent.linkTypes).Count -eq 2) 'CAS save silently dropped LinkTypes'
$report=Post ('/api/model/drafts/'+$id+'/validate') @{expectedRevision=2}
Must ($report.status -eq 'VALID' -and [long]$report.validatedRevision -eq 2) 'Complete 3+2 model should be VALID'
$key1=[Guid]::NewGuid().ToString()
$a=Post ('/api/model/drafts/'+$id+'/snapshots') @{expectedRevision=2;idempotencyKey=$key1}
Must ($a.payloadSha256 -match '^[a-f0-9]{64}$') 'First SHA-256 missing'
Must (@($a.snapshotContent.linkTypes).Count -eq 2) 'First snapshot lost links'
$replay=Post ('/api/model/drafts/'+$id+'/snapshots') @{expectedRevision=2;idempotencyKey=$key1}
Must ($replay.snapshotId -eq $a.snapshotId) 'Idempotency key replay created another snapshot'
$doc.linkTypes[0].cardinality='ONE_TO_ONE'
$doc.linkTypes[0].evidenceRequired=$false
$save=Call ('/api/model/drafts/'+$id) 'PUT' @{expectedRevision=2;editingContent=$doc}
Must ([long]$save.revision -eq 3 -and $save.validation.status -eq 'NOT_RUN') 'Revision 3 did not reset validation'
$report=Post ('/api/model/drafts/'+$id+'/validate') @{expectedRevision=3}
Must ($report.status -eq 'VALID') 'Revised LinkType invalid'
$key2=[Guid]::NewGuid().ToString()
$b=Post ('/api/model/drafts/'+$id+'/snapshots') @{expectedRevision=3;idempotencyKey=$key2}
Must ($b.snapshotId -ne $a.snapshotId -and $b.payloadSha256 -ne $a.payloadSha256) 'Second snapshot should be distinct'
RequireHttp409 ('/api/model/drafts/'+$id+'/snapshots') @{expectedRevision=3;idempotencyKey=([Guid]::NewGuid().ToString())}
$diff=Call ('/api/model/snapshot-diffs?from='+$a.snapshotId+'&to='+$b.snapshotId)
Must ($diff.compatibilityStatus -eq 'STRUCTURAL_RISK_ONLY_NOT_COMPATIBILITY_CERTIFICATION') 'Diff must not certify actual consumers'
Must (@($diff.changes|Where-Object {$_.path -match '/cardinality$' -and $_.riskLevel -eq 'POTENTIALLY_BREAKING'}).Count -eq 1) 'Expected breaking cardinality change'
Must (@($diff.changes|Where-Object {$_.reasonCode -eq 'LINK_EVIDENCE_RELAXED'}).Count -eq 1) 'Expected relaxed evidence governance warning'
$original=Call ('/api/model/snapshots/'+$a.snapshotId)
Must ($original.payloadSha256 -eq $a.payloadSha256 -and $original.snapshotContent.linkTypes[0].cardinality -eq 'MANY_TO_ONE') 'Original snapshot overwritten'
if([string]::IsNullOrWhiteSpace($EvidencePath)){
  $EvidencePath=Join-Path ([IO.Path]::GetTempPath()) ('ontoforge-linktype-acceptance-'+$token+'.json')
}
$record=@{
  schemaVersion='S0-MVP-0.4-linktype-acceptance-v1'
  createdUtc=[DateTime]::UtcNow.ToString('o')
  installationId=$status.installationId
  packageId=$package;draftId=$id
  snapshots=@(
    @{id=$a.snapshotId;sha256=$a.payloadSha256;revision=2},
    @{id=$b.snapshotId;sha256=$b.payloadSha256;revision=3}
  )
  nativeWindowsPostgresRestart='NOT_RUN'
}
ConvertTo-Json -InputObject $record -Depth 20 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
Write-Host '[PASS] Real loopback PG/API: 3 object types + 2 links, immutable history, LinkType risk Diff and CAS 409.'
Write-Host ("[PASS] Evidence: "+$EvidencePath)
Write-Host 'Restart Spring Boot without resetting native Windows PostgreSQL, then execute:'
Write-Host ('.\scripts\verify-linktype.ps1 -Mode Recheck -EvidencePath "'+$EvidencePath+'"')
Write-Host '[NOTE] All isolated test drafts and snapshots remain in the test database; no destructive cleanup.'
