# Windows regression checks at the real Java properties/NTFS boundary; no database required.
$ErrorActionPreference = 'Stop'
. (Join-Path (Split-Path -Parent $PSScriptRoot) 'local-environment.ps1')
$directory = Join-Path ([IO.Path]::GetTempPath()) ('ontoforge-local-config-test-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($directory)
$path = Join-Path $directory 'application.properties'
$before = @{}
$names = @('LOCAL_STARTUP_EXPECTED', 'ONTOFORGE_SECURITY_AUTHENTICATION_MODE', 'SPRING_PROFILES_ACTIVE',
    'DB_PASSWORD', 'ONTOFORGE_OBJECTSET_CURSOR_SIGNING_SECRET', 'CI', 'SPRING_DATASOURCE_PASSWORD')
foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $special = ' ' + [char]0x5bc6 + [char]0x7801 + ':=#!\literal\u1234' + "`t" + [char]0xd83d + [char]0xde80 + ' '
    $values = @{'spring.datasource.password'=$special; 'ontoforge.objectset.cursor-signing-secret'='test-only-' + [guid]::NewGuid()}
    Write-LocalProperties $path $values
    $env:LOCAL_STARTUP_EXPECTED = $special
    $source = Join-Path $directory 'CheckLocalProperties.java'
    [IO.File]::WriteAllText($source, @'
import java.nio.file.*;
import java.util.Properties;
public class CheckLocalProperties {
    public static void main(String[] args) throws Exception {
        var properties=new Properties();
        try(var input=Files.newInputStream(Path.of(args[0]))) { properties.load(input); }
        if(!System.getenv("LOCAL_STARTUP_EXPECTED").equals(properties.getProperty("spring.datasource.password")))
            throw new AssertionError("Java did not recover the exact configured credential");
        System.out.println("PASS: real Java properties preserve Unicode, spaces, slashes and punctuation");
    }
}
'@)
    & java $source $path
    if ($LASTEXITCODE -ne 0) { throw 'Java credential decoding failed.' }
    $acl = Get-Acl -LiteralPath $path
    $current = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if (-not $acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -ne $current) { throw 'Local credential ACL is not restricted to the current user.' }
    $loaded = Read-LocalProperties $path
    if ($loaded['spring.datasource.password'] -cne $special) { throw 'Saved local credential was not preserved.' }
    Write-Host 'PASS: local credentials have a current-user-only NTFS ACL'
    $foreign = Join-Path $directory 'foreign.properties'
    [IO.File]::WriteAllText($foreign, 'unowned=true')
    $refused = $false
    try { [void](Read-LocalProperties $foreign) } catch { $refused = $true }
    if (-not $refused -or [IO.File]::ReadAllText($foreign) -ne 'unowned=true') { throw 'Unowned configuration was modified.' }
    Write-Host 'PASS: unowned configuration is refused and preserved'
    $env:CI = ''; $env:SPRING_PROFILES_ACTIVE = ''; $env:ONTOFORGE_SECURITY_AUTHENTICATION_MODE = 'oidc-jwt'
    if ($null -ne (Initialize-LocalEnvironment $directory '' $false $false)) { throw 'JWT mode unexpectedly initialized local accounts.' }
    $env:ONTOFORGE_SECURITY_AUTHENTICATION_MODE = ''; $env:SPRING_PROFILES_ACTIVE = 'mvp-local'
    if ($null -ne (Initialize-LocalEnvironment $directory '' $false $false)) { throw 'mvp-local unexpectedly initialized accounts.' }
    $env:SPRING_PROFILES_ACTIVE = ''; $env:DB_PASSWORD = ''; $env:ONTOFORGE_OBJECTSET_CURSOR_SIGNING_SECRET = ''
    $refused = $false
    try { [void](Initialize-LocalEnvironment $directory '' $true $false) } catch { $refused = $true }
    if (-not $refused -or (Test-Path -LiteralPath (Join-Path $directory 'config/application.properties'))) { throw 'Non-interactive startup unexpectedly prompted or wrote defaults.' }
    Write-Host 'PASS: JWT/mvp-local remain explicit; unattended missing credentials fail without prompting'
    $env:ONTOFORGE_SECURITY_AUTHENTICATION_MODE = 'oidc-jwt'; $env:DB_PASSWORD = 'test-only-external-override'
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    [void](Initialize-LocalEnvironment $directory '' $true $false)
    if ($env:SPRING_DATASOURCE_PASSWORD -cne $env:DB_PASSWORD) { throw 'An explicit database credential did not override a saved configuration.' }
    Write-Host 'PASS: explicit database environment wins over a saved local configuration in JWT mode'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $before[$name], 'Process') }
    # Remove only the exact files created by this test; no recursive cleanup of computed paths.
    foreach ($file in @($path, $source, (Join-Path $directory 'foreign.properties'))) {
        if ($file -and (Test-Path -LiteralPath $file -PathType Leaf)) { Remove-Item -LiteralPath $file -Force }
    }
    if ((Get-ChildItem -LiteralPath $directory -Force).Count -eq 0) { Remove-Item -LiteralPath $directory }
}
