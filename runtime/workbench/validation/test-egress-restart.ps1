[CmdletBinding()]
param([Parameter(Mandatory)][string]$DataDirectory, [Parameter(Mandatory)][guid]$ProjectId,
    [Parameter(Mandatory)][int]$TestBackendPort)
$ErrorActionPreference = 'Stop'
$folder = Join-Path (Resolve-Path -LiteralPath $DataDirectory).Path "environments/$ProjectId"
$manifest = Get-Content -LiteralPath (Join-Path $folder 'environment.json') -Raw | ConvertFrom-Json
if ($manifest.project -ne $ProjectId.ToString()) { throw '测试项目登记不匹配' }
$origin = "http://127.0.0.1:$TestBackendPort"
Invoke-RestMethod -Method Post -Uri "$origin/api/workbench/projects/$ProjectId/environment" -Headers @{Origin=$origin} `
    -ContentType 'application/json' -Body '{"action":"Restart"}'
$id = (& docker inspect --format '{{.Id}}' $manifest.container).Trim()
if ($LASTEXITCODE -ne 0 -or $id -ne $manifest.containerId) { throw '普通重启更换了测试项目的开发容器' }
$probe = & docker exec $id curl --fail --silent --show-error --max-time 25 --output /dev/null --write-out '%{http_code} %{remote_port}' https://repo.maven.apache.org/maven2/org/apache/maven/maven-core/3.9.9/maven-core-3.9.9.pom
if ($LASTEXITCODE -ne 0 -or $probe -ne '200 3128') { throw "重启后无法从出口代理下载制品：$probe" }
Write-Host '同一项目容器已通过出口代理取得 HTTP 200。'
