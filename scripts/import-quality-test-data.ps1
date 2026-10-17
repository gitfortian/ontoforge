[CmdletBinding()]
param(
  [string]$Database = 'ontoforge_test_quality',
  [string]$HostName = '127.0.0.1',
  [ValidateRange(1,65535)][int]$Port = 5432,
  [string]$DbUser = 'postgres',
  [string]$Psql = 'psql',
  [string]$CsvDirectory = (Join-Path $PSScriptRoot 'mvp-quality-csv')
)
$ErrorActionPreference = 'Stop'
if ($Database -cnotmatch '^ontoforge_test_[a-z0-9_]+$') {
  throw 'REFUSED: database must be a dedicated ontoforge_test_* database.'
}
if ($HostName -notin @('localhost','127.0.0.1')) {
  throw 'REFUSED: importer only permits loopback PostgreSQL.'
}
if (-not (Get-Command $Psql -ErrorAction SilentlyContinue)) {
  throw 'psql client missing; put PostgreSQL bin in PATH.'
}
$names = @('sales_orders.csv','prod_jobs.csv','quality_lots.csv')
$headers = @('sales_no,buyer','job_no,sales_ref','lot_no,outcome,job_ref')
$rows = @{}
$paths = @{}
for($i=0; $i -lt $names.Count; $i++){
  $path=Join-Path $CsvDirectory $names[$i]
  if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing CSV: $($names[$i])"}
  $full=(Resolve-Path -LiteralPath $path).ProviderPath.Replace('\','/')
  if($full.Contains("'") -or $full.Contains([char]10) -or $full.Contains([char]13)){
    throw 'CSV path contains an unsafe quote or newline.'
  }
  $first=(Get-Content -LiteralPath $path -Encoding UTF8 -TotalCount 1).TrimStart([char]0xFEFF)
  if($first -cne $headers[$i]){throw "Invalid header in $($names[$i]); expected $($headers[$i])"}
  $items=@(Import-Csv -LiteralPath $path -Encoding UTF8)
  if($items.Count -lt 1 -or $items.Count -gt 10000){throw 'Each CSV must contain 1..10000 rows.'}
  foreach($item in $items){
    foreach($col in ($headers[$i] -split ',')){
      if([string]::IsNullOrWhiteSpace([string]$item.$col)){throw "Missing $col in $($names[$i])"}
    }
  }
  $rows[$names[$i]]=$items
  $paths[$names[$i]]=$full
}
$orders=@{}
foreach($o in $rows['sales_orders.csv']){
  if($orders.ContainsKey($o.sales_no)){throw 'Duplicate sales_no'}
  $orders[$o.sales_no]=$true
}
$jobs=@{}
foreach($j in $rows['prod_jobs.csv']){
  if($jobs.ContainsKey($j.job_no)){throw 'Duplicate job_no'}
  if(-not $orders.ContainsKey($j.sales_ref)){throw 'Unknown sales_ref'}
  $jobs[$j.job_no]=$true
}
$lots=@{}
foreach($b in $rows['quality_lots.csv']){
  if($lots.ContainsKey($b.lot_no)){throw 'Duplicate lot_no'}
  if(-not $jobs.ContainsKey($b.job_ref)){throw 'Unknown job_ref'}
  $lots[$b.lot_no]=$true
}
$sql=@"
BEGIN;
CREATE TABLE IF NOT EXISTS public.demo_sales_orders(sales_no TEXT PRIMARY KEY,buyer TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS public.demo_prod_jobs(job_no TEXT PRIMARY KEY,sales_ref TEXT NOT NULL REFERENCES public.demo_sales_orders(sales_no));
CREATE TABLE IF NOT EXISTS public.demo_quality_lots(lot_no TEXT PRIMARY KEY,outcome TEXT NOT NULL,job_ref TEXT NOT NULL REFERENCES public.demo_prod_jobs(job_no));
TRUNCATE public.demo_quality_lots,public.demo_prod_jobs,public.demo_sales_orders;
\copy public.demo_sales_orders(sales_no,buyer) FROM '$($paths['sales_orders.csv'])' WITH (FORMAT csv,HEADER true)
\copy public.demo_prod_jobs(job_no,sales_ref) FROM '$($paths['prod_jobs.csv'])' WITH (FORMAT csv,HEADER true)
\copy public.demo_quality_lots(lot_no,outcome,job_ref) FROM '$($paths['quality_lots.csv'])' WITH (FORMAT csv,HEADER true)
COMMIT;
"@
$tmp=Join-Path ([IO.Path]::GetTempPath()) ('ontoforge-test-import-'+[guid]::NewGuid().ToString('N')+'.sql')
try {
  [IO.File]::WriteAllText($tmp,$sql,(New-Object Text.UTF8Encoding($false)))
  & $Psql -X -w -v ON_ERROR_STOP=1 -h $HostName -p $Port -U $DbUser -d $Database -f $tmp
  if($LASTEXITCODE -ne 0){throw "Import failed (code $LASTEXITCODE), transaction rolled back."}
  Write-Host "SUCCESS: imported sanitized local CSV: lots=$($lots.Count), jobs=$($jobs.Count), orders=$($orders.Count)."
  Write-Host 'Open OntoForge quality MVP; map demo_* tables, preview links, save binding, then query a lot_no.'
} finally {
  Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}
