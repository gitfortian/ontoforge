<#
.SYNOPSIS
  Preview main's PR/quality rules; -Apply requires an authenticated GitHub admin.
.DESCRIPTION
  Never run automatically in CI. Apply only after the consumer-aware E2E gate
  has merged and passed on remote main. No credentials are stored or printed.
#>
[CmdletBinding()]
param(
  [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
  [string]$Repository='gitfortian/ontoforge',
  [switch]$Apply
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$payloadPath=Join-Path $root '.github/main-ruleset.json'
$payload=Get-Content -Raw -LiteralPath $payloadPath
if(-not $Apply){
  Write-Output $payload
  Write-Host 'Preview only. After merging and verifying the gate, an authenticated repository admin may use -Apply.'
  return
}
if(-not (Get-Command gh -ErrorAction SilentlyContinue)){
  throw 'GitHub CLI (gh) and repository administration permission are required. No settings changed.'
}
function ReadGitHub([string]$endpoint){
  $result=& gh api $endpoint
  if($LASTEXITCODE -ne 0){throw 'GitHub read failed; no settings changed.'}
  return ($result | ConvertFrom-Json)
}
$branch=ReadGitHub "repos/$Repository/branches/main"
$workflow=ReadGitHub "repos/$Repository/contents/.github/workflows/quality-gate.yml?ref=$($branch.commit.sha)"
$source=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($workflow.content))
if($source -notmatch 'needs:\s*\[scope, docs, native, backend, frontend, poc, e2e\]'){
  throw 'Remote main has not yet integrated real E2E into the stable gate. No settings changed.'
}
$checks=ReadGitHub "repos/$Repository/commits/$($branch.commit.sha)/check-runs?per_page=100"
$gate=@($checks.check_runs | Where-Object {$_.name -eq 'Required Quality Gate'} |
  Sort-Object started_at -Descending | Select-Object -First 1)
if($gate.Count -ne 1 -or $gate[0].conclusion -ne 'success' -or $gate[0].app.id -ne 15368){
  throw 'Expected successful GitHub Actions Required Quality Gate on current main. No settings changed.'
}
$rules=@(ReadGitHub "repos/$Repository/rulesets?per_page=100" |
  Where-Object {$_.name -eq 'main' -and $_.source -eq $Repository})
if($rules.Count -gt 1){throw 'Multiple repository main rulesets require manual review. No settings changed.'}
$method='POST';$endpoint="repos/$Repository/rulesets"
if($rules.Count -eq 1){$method='PUT';$endpoint+='/'+$rules[0].id}
$result=& gh api --method $method $endpoint --input $payloadPath
if($LASTEXITCODE -ne 0){throw 'GitHub did not confirm application. Inspect repository settings before retrying.'}
$saved=$result | ConvertFrom-Json
$verified=ReadGitHub "repos/$Repository/rulesets/$($saved.id)"
if($verified.enforcement -ne 'active' -or 'refs/heads/main' -notin $verified.conditions.ref_name.include){
  throw 'Ruleset activation verification failed.'
}
$statusRules=@($verified.rules | Where-Object {$_.type -eq 'required_status_checks'})
if($statusRules.Count -ne 1 -or
   -not $statusRules[0].parameters.strict_required_status_checks_policy -or
   @($statusRules[0].parameters.required_status_checks | Where-Object {
     $_.context -eq 'Required Quality Gate' -and $_.integration_id -eq 15368
   }).Count -ne 1 -or
   'pull_request' -notin @($verified.rules.type) -or $verified.bypass_actors.Count -ne 0){
  throw 'Ruleset requirements verification failed; inspect settings before claiming protection.'
}
$protected=ReadGitHub "repos/$Repository/branches/main"
if(-not $protected.protected){throw 'main is not effectively protected despite saved ruleset. Inspect repository eligibility/settings.'}
Write-Host ('[PASS] Active main ruleset '+$verified.id+' requires PR and GitHub Actions Required Quality Gate.')
Write-Host 'Verify a deliberately failing test PR is blocked; configuration alone is not negative merge evidence.'
