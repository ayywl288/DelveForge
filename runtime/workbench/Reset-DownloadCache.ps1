[CmdletBinding()]
param([Parameter(Mandatory)][string]$DataDirectory, [ValidateSet('Status','Clear')][string]$Action='Status')

$ErrorActionPreference = 'Stop'
$dataPath = (Resolve-Path -LiteralPath $DataDirectory).Path
$catalog = Get-Content -LiteralPath (Join-Path $dataPath 'runtime-catalog.json') -Raw | ConvertFrom-Json
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($dataPath.ToLowerInvariant()))).Replace('-','').ToLowerInvariant() }
finally { $sha.Dispose() }
$rootOwner = 'df-wb-' + $digest.Substring(0,12)
if ($catalog.owner -cne $rootOwner -or $catalog.endpoint -notlike 'npipe:*') { throw '本机下载缓存目录归属待核对' }
function Invoke-Engine([string[]]$Arguments) {
    $result = & docker --host $catalog.endpoint @Arguments
    if ($LASTEXITCODE) { throw "Docker $($Arguments[0]) 失败" }
    return $result
}
. (Join-Path $PSScriptRoot 'DownloadCache.ps1')
$directory = Join-Path $dataPath 'download-cache'
$file = Join-Path $directory 'cache.json'
if (-not [IO.File]::Exists($file)) { Write-Output '该工作台尚无下载缓存'; return }
$lock = Enter-CacheLock $directory
try {
    $registered = Get-Content -Raw -LiteralPath $file | ConvertFrom-Json
    $name = "$rootOwner-central"
    $volume = "$name-downloads"
    if ($registered.owner -ne $rootOwner -or $registered.volume -ne $volume -or $registered.name -ne $name) { throw '下载卷归属异常' }
    $id = (Invoke-Engine @('inspect','--format','{{.Id}}',$name)).Trim()
    if ($id -ne $registered.id) { throw '下载缓存容器已替换' }
    Confirm-CacheOwner container $id
    Confirm-CacheOwner volume $volume
    $mounts = Invoke-Engine @('inspect','--format','{{json .Mounts}}',$id) | ConvertFrom-Json
    if (@($mounts | Where-Object { $_.Type -ceq 'volume' -and $_.Name -ceq $volume -and $_.Destination -ceq '/cache' -and $_.RW }).Count -ne 1) {
        throw '下载卷挂载异常'
    }
    $image = (Invoke-Engine @('inspect','--format','{{.Image}}',$id)).Trim()
    if ($image -ne $registered.image) { throw '下载缓存镜像身份已变化' }
    $active = (Invoke-Engine @('inspect','--format','{{.State.Running}}',$id)).Trim() -eq 'true'
    if ($Action -eq 'Status') {
        $size = Invoke-Engine @('run','--rm','--network','none','--read-only','--cap-drop','ALL','--user','nginx',
            '--mount',"type=volume,src=$volume,target=/cache,readonly",'--entrypoint','du',$image,'-sh','/cache/central')
        Write-Output "$name running=$active $size"
        return
    }
    if ($active) { Invoke-Engine @('stop',$id) | Out-Null }
    try {
        Invoke-Engine @('run','--rm','--network','none','--read-only','--cap-drop','ALL','--user','nginx',
            '--mount',"type=volume,src=$volume,target=/cache",'--entrypoint','find',$image,'/cache/central','-mindepth','1','-delete') | Out-Null
    } finally { if ($active) { Invoke-Engine @('start',$id) | Out-Null } }
    Write-Output "已清空下载卷 $volume 的 Central 缓存"
} finally { $lock.Dispose() }
