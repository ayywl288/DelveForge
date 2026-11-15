[CmdletBinding()]
param(
    [string]$DataDirectory = (Join-Path $PSScriptRoot '..\..\data\workbench'),
    [ValidateSet('Ensure','Restart','Rebuild')][string]$Action = 'Ensure'
)
$ErrorActionPreference = 'Stop'
function Invoke-Engine([string[]]$Arguments) {
    $result = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker $($Arguments[0]) 失败。" }
    return $result
}
function Get-Digest([string]$value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($value)))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
$dataPath = [IO.Path]::GetFullPath($DataDirectory)
$projectPath = Join-Path $dataPath 'projects'
$certPath = Join-Path $dataPath 'tls'
$owner = 'df-wb-' + (Get-Digest $dataPath.ToLowerInvariant()).Substring(0,12)
$label = "dev.delveforge.workbench=$owner"
$inside = "$owner-inside"
$outside = "$owner-edge"
$stateVolume = "$owner-state"
$dev = "$owner-dev"
$proxy = "$owner-egress"
$gate = "$owner-gateway"
$edge = "$owner-ingress"
$endpoints = (Invoke-Engine @('context','inspect','--format','{{json .Endpoints}}')) | ConvertFrom-Json
$endpoint = [string]$endpoints.docker.Host
if ($endpoint -notlike 'npipe:*' -or (Invoke-Engine @('info','--format','{{.OSType}}')).Trim() -ne 'linux') {
    throw '请选择本机 Docker Desktop Linux 引擎。'
}
$images = @{}
foreach ($role in @('development','egress','gateway','edge')) {
    $context = Join-Path $PSScriptRoot $role
    $hashes = Get-ChildItem -LiteralPath $context -File | Sort-Object Name | ForEach-Object {
        $_.Name + ':' + (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
    }
    $tag = 'df-workbench/' + $role + ':' + (Get-Digest ($hashes -join ',')).Substring(0,16)
    $images[$role] = $tag
    $available = Invoke-Engine @('image','ls','--format','{{.Repository}}:{{.Tag}}',$tag)
    if ($available -notcontains $tag) {
        Invoke-Engine @('build','--label','dev.delveforge.workbench.image=true','-t',$tag,$context) | Out-Host
    }
}
function Assert-Owned([string]$type,[string]$name) {
    if ($type -eq 'container') { $format = '{{json .Config.Labels}}' }
    else { $format = '{{json .Labels}}' }
    $resourceLabels = (Invoke-Engine @($type,'inspect','--format',$format,$name)) | ConvertFrom-Json
    if ($resourceLabels.'dev.delveforge.workbench' -ne $owner) {
        throw "名称已被其他资源占用：$name"
    }
}
# 在停启任何组件前核对整组，避免最后一个镜像过期时留下半启动的环境。
$names = @(Invoke-Engine @('ps','-a','--format','{{.Names}}'))
$roles = [ordered]@{development=$dev; egress=$proxy; gateway=$gate; edge=$edge}
$existing = @{}
$outdated = @(foreach ($role in $roles.Keys) {
    $name = $roles[$role]
    if ($names -notcontains $name) { continue }
    $identity = (Invoke-Engine @('container','inspect','--format','{{.Id}} {{.Image}}',$name)).Trim().Split(' ')
    Assert-Owned 'container' $identity[0]
    $existing[$name] = $identity[0]
    if ($Action -ne 'Rebuild') {
        $expected = (Invoke-Engine @('image','inspect','--format','{{.Id}}',$images[$role])).Trim()
        if ($identity[1] -ne $expected) { $role }
    }
})
if ($outdated.Count -gt 0) {
    $command = '.\setup-delveforge.ps1 -EnvironmentAction Rebuild'
    $defaultPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\data\workbench'))
    if ($dataPath -ne $defaultPath) { $command += " -DataDirectory '" + $dataPath.Replace("'","''") + "'" }
    $failure = [InvalidOperationException]::new(
            "工作台组件需要更新镜像：$($outdated -join ', ')。重建会终止后台服务，容器系统层里另装的软件需重新安装。工作副本、对话和数据卷会保留。也可在仓库根目录运行 $command")
    $failure.Data['DelveForgeImageMismatch'] = $true
    throw $failure
}
foreach ($path in @($dataPath,$projectPath,$certPath)) {
    [IO.Directory]::CreateDirectory($path) | Out-Null
}
$networks = @(Invoke-Engine @('network','ls','--format','{{.Name}}'))
foreach ($name in @($inside,$outside)) {
    if ($networks -contains $name) { Assert-Owned 'network' $name }
    else {
        $netArgs = @('network','create','--label',$label)
        if ($name -eq $inside) { $netArgs += @('--internal','--opt','com.docker.network.bridge.gateway_mode_ipv4=isolated') }
        Invoke-Engine ($netArgs + $name) | Out-Null
    }
}
if (@(Invoke-Engine @('volume','ls','--format','{{.Name}}')) -contains $stateVolume) { Assert-Owned 'volume' $stateVolume }
else { Invoke-Engine @('volume','create','--label',$label,$stateVolume) | Out-Null }
if (-not (Test-Path -LiteralPath (Join-Path $certPath 'client/client.key'))) {
    Invoke-Engine @('run','--rm','--network','none','--cap-drop','ALL','--user','0:0',
        '--mount',"type=bind,src=$certPath,target=/keys",'--entrypoint','/bin/sh',$images.edge,'/certs.sh') | Out-Null
}
$gatewayCert = Join-Path $certPath 'gateway'
$hostRules = Join-Path $dataPath 'egress'
[IO.Directory]::CreateDirectory($hostRules) | Out-Null
$addresses = @(foreach ($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    foreach ($unicast in $adapter.GetIPProperties().UnicastAddresses) { $unicast.Address }
})
$rules = @('flush set inet delveforge windows4','flush set inet delveforge windows6')
foreach ($family in @('InterNetwork','InterNetworkV6')) {
    $set = if ($family -eq 'InterNetwork') { 'windows4' } else { 'windows6' }
    $ips = @($addresses | Where-Object { $_.AddressFamily.ToString() -eq $family } |
        ForEach-Object { $_.ToString().Split('%')[0] } | Sort-Object -Unique)
    if ($ips.Count -gt 0) { $rules += "add element inet delveforge $set { $($ips -join ', ') }" }
}
$ruleFile = Join-Path $hostRules 'addresses.nft'
$ruleTemp = Join-Path $hostRules ([Guid]::NewGuid().ToString() + '.tmp')
[IO.File]::WriteAllText($ruleTemp,($rules -join "`n") + "`n",[Text.Encoding]::ASCII)
if ([IO.File]::Exists($ruleFile)) {
    $backup = $ruleTemp + '.previous'
    [IO.File]::Replace($ruleTemp,$ruleFile,$backup)
    [IO.File]::Delete($backup)
} else { [IO.File]::Move($ruleTemp,$ruleFile) }
# Docker Desktop 保留 Linux 所有者。只让网关的独立 UID 读取自己的证书，不挂载客户端私钥。
Invoke-Engine @('run','--rm','--network','none','--cap-drop','ALL','--cap-add','CHOWN','--cap-add','DAC_OVERRIDE',
    '--user','0:0','--mount',"type=bind,src=$gatewayCert,target=/cert",'--entrypoint','chown',$images.edge,
    '65534:65534','/cert','/cert/server.key','/cert/server.crt','/cert/ca.crt') | Out-Null
if ($Action -eq 'Rebuild') {
    Write-Host '重建会终止该环境后台服务、丢弃系统层安装，保留工作副本和数据卷。'
    foreach ($name in @($gate,$edge,$proxy,$dev)) {
        if ($existing.ContainsKey($name)) { Invoke-Engine @('rm','-f',$existing[$name]) | Out-Null }
    }
    $existing.Clear()
} elseif ($Action -eq 'Restart') {
    Write-Host '重启会终止该环境后台服务，保留已安装的软件。'
    foreach ($name in @($gate,$edge,$proxy,$dev)) {
        if ($existing.ContainsKey($name)) { Invoke-Engine @('stop',$existing[$name]) | Out-Null }
    }
}
function Start-Owned([string]$name,[string]$image,[string[]]$argsForCreate,[bool]$external) {
    $id = $existing[$name]
    if (-not $id) {
        $create = @('create','--name',$name,'--label',$label,'--security-opt','no-new-privileges') + $argsForCreate + $image
        $id = (Invoke-Engine $create).Trim()
    }
    if ($external) {
        $attached = (Invoke-Engine @('inspect','--format','{{json .NetworkSettings.Networks}}',$id)) | ConvertFrom-Json
        if ($null -eq $attached.PSObject.Properties[$outside]) {
            Invoke-Engine @('network','connect','--gw-priority','1',$outside,$id) | Out-Null
        }
    }
    if ((Invoke-Engine @('inspect','--format','{{.State.Running}}',$id)).Trim() -ne 'true') {
        Invoke-Engine @('start',$id) | Out-Null
    }
}
$devArgs = @('--network',$inside,'--network-alias','dev','--user','0:0','--cap-drop','ALL',
    '--cap-add','CHOWN','--cap-add','DAC_OVERRIDE','--cap-add','FOWNER','--cap-add','SETUID','--cap-add','SETGID',
    '--cap-add','SYS_CHROOT','--mount',"type=bind,src=$projectPath,target=/projects",
    '--mount',"type=volume,src=$stateVolume,target=/state",'--pids-limit','768')
foreach ($key in @('http_proxy','https_proxy','HTTP_PROXY','HTTPS_PROXY')) { $devArgs += @('-e',"$key=http://egress:3128") }
$devArgs += @('-e','NO_PROXY=localhost,127.0.0.1','-e','no_proxy=localhost,127.0.0.1','-e','npm_config_cache=/state/cache/npm','-e','PNPM_CONFIG_STORE_DIR=/state/cache/pnpm')
Start-Owned $dev $images.development $devArgs $false
Start-Owned $proxy $images.egress @('--network',$inside,'--network-alias','egress','--cap-add','NET_ADMIN',
    '--sysctl','net.ipv4.ip_forward=0','--mount',"type=bind,src=$hostRules,target=/host,readonly") $true
Start-Owned $gate $images.gateway @('--network',"container:$dev",'--read-only','--cap-drop','ALL',
    '--mount',"type=bind,src=$gatewayCert,target=/cert,readonly") $false
Start-Owned $edge $images.edge @('--network',$inside,'--read-only','--cap-drop','ALL','--sysctl','net.ipv4.ip_forward=0',
    '-p','127.0.0.1::8140','-p','127.0.0.1::8141') $true
function Get-Port([int]$port) {
    $binding=(Invoke-Engine @('port',$edge,"$port/tcp")).Trim()
    if ($binding -notmatch '^127\.0\.0\.1:(\d+)$') { throw '预览端口没有绑定 Windows 回环地址。' }
    return [int]$Matches[1]
}
$info = [ordered]@{owner=$owner; endpoint=$endpoint; container=$dev; parent=$projectPath;
    previewPort=(Get-Port 8140); controlPort=(Get-Port 8141)}
$ready = $false
for ($attempt = 0; $attempt -lt 12; $attempt++) {
    $probe = & curl.exe --noproxy '*' --silent --max-time 2 --output NUL --write-out '%{http_code}' "http://127.0.0.1:$($info.previewPort)/"
    if ($LASTEXITCODE -eq 0 -and $probe -eq '404') { $ready = $true; break }
    Start-Sleep -Milliseconds 250
}
if (-not $ready) { throw 'Windows 回环入口未连通 Go 网关；请检查该环境的 gateway 与 ingress 容器。' }
$guardReady = $false
for ($attempt = 0; $attempt -lt 8; $attempt++) {
    & docker exec $proxy nft -f /host/addresses.nft 2>$null
    if ($LASTEXITCODE -eq 0) { $guardReady = $true; break }
    Start-Sleep -Milliseconds 300
}
if (-not $guardReady) {
    Invoke-Engine @('stop',$proxy) | Out-Null
    throw '无法在代理内加载宿主地址限制，已关闭该环境的下载代理。'
}
$configFile = Join-Path $dataPath 'environment.json'
$temp = "$configFile.$([Guid]::NewGuid().ToString('N')).tmp"
[IO.File]::WriteAllText($temp,($info | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
if ([IO.File]::Exists($configFile)) {
    $previous = "$temp.previous"
    [IO.File]::Replace($temp,$configFile,$previous)
    [IO.File]::Delete($previous)
}
else { [IO.File]::Move($temp,$configFile) }
Write-Host "工作副本目录 $projectPath"
Write-Host "Windows 回环预览端口 $($info.previewPort)"
