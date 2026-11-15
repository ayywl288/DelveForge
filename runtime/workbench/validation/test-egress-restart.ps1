[CmdletBinding()]
param([Parameter(Mandatory)][string]$DataDirectory)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $DataDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $root 'environment.json') -Raw | ConvertFrom-Json
if ([IO.Path]::GetFullPath($manifest.parent) -ne (Join-Path $root 'projects')) {
    throw '清单与指定的测试环境不一致。'
}
$devId = & docker inspect --format '{{.Id}}' $manifest.container
if ($LASTEXITCODE -ne 0) { throw '测试开发环境不存在。' }

# 走启动器的普通重启路径，保留同一开发容器。随后通过受控出口请求一个很小的公开 Maven 文件。
& (Join-Path $PSScriptRoot '../Initialize-Environment.ps1') -DataDirectory $root -Action Restart
$afterId = & docker inspect --format '{{.Id}}' $manifest.container
if ($LASTEXITCODE -ne 0 -or $devId -ne $afterId) { throw '普通重启更换了开发容器。' }
$probe = & docker exec $manifest.container curl --fail --silent --show-error --max-time 25 --output /dev/null --write-out '%{http_code} %{remote_port}' https://repo.maven.apache.org/maven2/org/apache/maven/maven-core/3.9.9/maven-core-3.9.9.pom
if ($LASTEXITCODE -ne 0 -or $probe -ne '200 3128') { throw "普通重启后未能通过出口代理下载制品：$probe" }
Write-Host "同一开发容器，Maven 制品 HTTP 200，代理端口 3128。"
