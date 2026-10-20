[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ScopePath,
    [Parameter(Mandatory=$true)][string]$Question,
    [PSCredential]$Credential,
    [string]$ApplicationUrl = 'http://127.0.0.1:8080',
    [switch]$LocalMvp,
    [switch]$ReadEvidence
)
$ErrorActionPreference = 'Stop'
$target = [Uri]$ApplicationUrl
if ($target.Scheme -ne 'http' -or $target.Host -notin @('127.0.0.1','[::1]','::1') -or
    $target.AbsolutePath -ne '/' -or $target.Query -or $target.Fragment -or $target.UserInfo) {
    throw 'This development client requires a literal loopback application URL.'
}
$root = $ApplicationUrl.TrimEnd('/')
$scope = Get-Content -LiteralPath $ScopePath -Raw | ConvertFrom-Json
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$headers = @{}
if (-not $LocalMvp) {
    if ($null -eq $Credential) { $Credential = Get-Credential -Message 'OntoForge local account (not the model API key)' }
    $context = Invoke-RestMethod -Uri "$root/api/auth/context" -WebSession $session
    $headers[$context.csrf.headerName] = $context.csrf.token
    $login = @{ username=$Credential.UserName; password=$Credential.GetNetworkCredential().Password } | ConvertTo-Json -Compress
    try {
        $null = Invoke-RestMethod -Method Post -Uri "$root/api/auth/login" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $login
    } finally { $login = $null }
    $context = Invoke-RestMethod -Uri "$root/api/auth/context" -WebSession $session
    $headers[$context.csrf.headerName] = $context.csrf.token
}
try {
    $payload = @{runVersion='local-agent-v1';question=$Question;scope=$scope} | ConvertTo-Json -Depth 30 -Compress
    $result = Invoke-RestMethod -Method Post -Uri "$root/api/agent/v1/runs" -WebSession $session -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($payload)) -TimeoutSec 65
    $result | ConvertTo-Json -Depth 60
    if ($ReadEvidence) {
        foreach ($reference in $result.evidenceRefs) {
            $encoded = [Uri]::EscapeDataString($reference)
            Invoke-RestMethod -Uri "$root/api/context/v1/evidence/$encoded" -WebSession $session | ConvertTo-Json -Depth 60
        }
    }
} finally {
    if (-not $LocalMvp) {
        try { $null = Invoke-RestMethod -Method Post -Uri "$root/api/auth/logout" -WebSession $session -Headers $headers }
        catch { Write-Warning 'The local session could not be logged out; it will expire under the configured timeout.' }
    }
}
