param([string]$Jar)
$ErrorActionPreference = 'Stop'
$repo = (Get-Item $PSScriptRoot).Parent.FullName
$data = Join-Path $repo 'target/eval-assets/service'
$build = Get-Content -Raw (Join-Path $repo 'target/eval-assets/agent-build.json') | ConvertFrom-Json
if (!$Jar) {
    $source = 'build-' + $build.commit.Substring(0,10) + '-' + $build.productDiffSha256.Substring(0,12)
    $Jar = Join-Path $repo "target/eval-assets/$source/backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar"
}
$Jar = (Get-Item -LiteralPath $Jar).FullName
$port = 18943
if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
    throw "Port $port is already in use."
}
& (Join-Path $repo 'runtime/workbench/Initialize-Environment.ps1') -DataDirectory $data
$file = Join-Path $data 'runtime-catalog.json'
$catalog = Get-Content -Raw $file | ConvertFrom-Json
$tag = 'df-eval/base-egress:' + $catalog.images.egress.Substring(7,16)
docker tag $catalog.images.egress $tag
if ($LASTEXITCODE) { throw 'Unable to identify base egress image' }
$context = Join-Path $repo 'target/eval-assets/policy'
[IO.Directory]::CreateDirectory($context) | Out-Null
$original = [IO.File]::ReadAllText((Join-Path $repo 'runtime/workbench/egress/squid.conf'))
if (($original.Split([string[]]@('http_access allow all'), 'None')).Length -ne 2) { throw 'Unexpected egress policy' }
$rules = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'downloads.acl')).Trim()
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $context 'squid.conf'), $original.Replace('http_access allow all',$rules), $utf8)
[IO.File]::WriteAllText((Join-Path $context 'Dockerfile'), "FROM $tag`nCOPY squid.conf /config/squid.conf`n", $utf8)
$policyHash = (Get-FileHash (Join-Path $context 'squid.conf')).Hash.Substring(0,16).ToLowerInvariant()
$policyTag = 'df-eval/egress:' + $catalog.images.egress.Substring(7,16) + '-' + $policyHash
$present = docker image ls --format '{{.Repository}}:{{.Tag}}' $policyTag
if ($LASTEXITCODE) { throw 'Unable to inspect evaluation image' }
if ($present -notcontains $policyTag) {
    docker build -q -t $policyTag $context | Out-Null
    if ($LASTEXITCODE) { throw 'Failed to build evaluation egress image' }
}
$image = docker image inspect --format '{{.Id}}' $policyTag
if ($LASTEXITCODE) { throw 'Unable to pin evaluation egress image' }
$catalog.images.egress = $image.Trim()
[IO.File]::WriteAllText($file, ($catalog | ConvertTo-Json -Depth 5), $utf8)
$jarHash = (Get-FileHash $Jar).Hash.ToLowerInvariant()
if ($jarHash -ne $build.jarSha256) { throw 'Use the verified JAR from build-agent' }
[IO.File]::WriteAllText((Join-Path $data 'eval-jar.sha256'), $jarHash, $utf8)
$arguments = @('-jar', ('"' + $Jar + '"'), "--server.port=$port", ('"--delveforge.workbench.directory=' + $data + '"'),
    '--delveforge.workbench.max-concurrent-runs=1','--delveforge.workbench.model.compaction-threshold=0.90',
    '--delveforge.workbench.model.context-window-tokens=1000000','--delveforge.workbench.model.response-tokens=65536',
    ('"--logging.file.name=' + $data + '/server.log"'))
$process = Start-Process java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru
Write-Output "Evaluation Workbench on 127.0.0.1:$port (PID $($process.Id))"
