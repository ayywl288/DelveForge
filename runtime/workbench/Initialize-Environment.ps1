[CmdletBinding()]
param(
    [string]$DataDirectory = (Join-Path $PSScriptRoot '..\..\data\workbench'),
    [ValidateSet('Ensure','Restart','Rebuild','Rollback','Recover')][string]$Action = 'Ensure',
    [string]$MaintenanceTicket,
    [string]$ProjectId,
    [ValidateRange(1,64)][int]$CpuLimit = 2,
    [ValidateRange(512,65536)][int]$MemoryMiB = 3072,
    [ValidateRange(64,16384)][int]$PidLimit = 768
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
function Save-Registration([string]$file,$value) {
    $tmp = "$file.$([Guid]::NewGuid().ToString('N')).tmp"
    [IO.File]::WriteAllText($tmp, ($value | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    if ([IO.File]::Exists($file)) {
        $backup = "$tmp.previous"
        [IO.File]::Replace($tmp,$file,$backup)
        [IO.File]::Delete($backup)
    } else { [IO.File]::Move($tmp,$file) }
}
$rootOwner = 'df-wb-' + (Get-Digest $dataPath.ToLowerInvariant()).Substring(0,12)
$catalogFile = Join-Path $dataPath 'runtime-catalog.json'
$endpoints = (Invoke-Engine @('context','inspect','--format','{{json .Endpoints}}')) | ConvertFrom-Json
$endpoint = [string]$endpoints.docker.Host
if ($endpoint -notlike 'npipe:*' -or (Invoke-Engine @('info','--format','{{.OSType}}')).Trim() -ne 'linux') {
    throw '请选择本机 Docker Desktop Linux 引擎。'
}
if (-not $ProjectId) {
    if ($Action -ne 'Ensure') { throw '请在项目的工作副本面板选择要维护的环境。旧共享环境保存在原位。' }
    [IO.Directory]::CreateDirectory($dataPath) | Out-Null
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
        $images[$role] = (Invoke-Engine @('image','inspect','--format','{{.Id}}',$tag)).Trim()
    }
    Save-Registration $catalogFile ([ordered]@{owner=$rootOwner;endpoint=$endpoint;images=$images})
    return
}
if ($ProjectId -cnotmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw '项目 ID 无效' }
$catalog = Get-Content -Raw -LiteralPath $catalogFile | ConvertFrom-Json
if ($catalog.owner -ne $rootOwner -or $catalog.endpoint -ne $endpoint) { throw '镜像目录与本机引擎不符' }
$projectPath = Join-Path (Join-Path $dataPath 'projects') $ProjectId
$projectMount = '/projects/' + $ProjectId
foreach ($dir in @($dataPath,(Join-Path $dataPath 'projects'),$projectPath)) {
    $entry = Get-Item -LiteralPath $dir -Force
    if (-not $entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw '项目目录已移动或指向外部位置' }
}
$namespace = $rootOwner + '-p' + $ProjectId.Replace('-','')
$stateVolume = "$namespace-state"
$controlDir = Join-Path (Join-Path $dataPath 'environments') $ProjectId
[IO.Directory]::CreateDirectory($controlDir) | Out-Null
$guard = [IO.File]::Open((Join-Path $controlDir 'provision.lock'),'OpenOrCreate','ReadWrite','None')
try {
$configFile = Join-Path $controlDir 'environment.json'
$pendingFile = Join-Path $controlDir 'provisioning.json'
$previous = if (Test-Path -LiteralPath $configFile) { Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json }
if ($Action -ne 'Ensure') {
    if ($MaintenanceTicket -notmatch '^[a-f0-9-]{36}$') { throw '请通过项目工作副本面板维护环境。' }
    $ticketFile = Join-Path $controlDir ('maintenance-' + $MaintenanceTicket)
    if (-not [IO.File]::Exists($ticketFile) -or [IO.File]::ReadAllText($ticketFile) -cne $Action) { throw '维护授权已失效' }
    [IO.File]::Delete($ticketFile)
}
$handover = Join-Path $controlDir 'handover.json'
function Get-GenerationMembers($registration) {
    $groupOwner = [string]$registration.owner
    if ($groupOwner -cnotmatch ('^'+[regex]::Escape($namespace)+'-g[0-9a-f]{12}$')) { throw '代次无效' }
    $members = [ordered]@{}
    $groupNames = @(Invoke-Engine @('ps','-a','--format','{{.Names}}'))
    foreach ($role in @('dev','egress','gateway','ingress')) {
        $name = "$groupOwner-$role"
        if ($groupNames -notcontains $name) { continue }
        $resource = @(Invoke-Engine @('container','inspect',$name) | Out-String | ConvertFrom-Json)[0]
        $labels = $resource.Config.Labels
        if ($labels.'dev.delveforge.workbench' -cne $groupOwner -or $labels.'dev.delveforge.project' -cne $ProjectId -or
                $labels.'dev.delveforge.installation' -cne $rootOwner -or
                ($role -eq 'dev' -and $registration.containerId -and $resource.Id -cne $registration.containerId)) {
            throw '容器归属或原容器身份异常，已保留现场'
        }
        $members[$role] = [ordered]@{id=$resource.Id;running=[bool]$resource.State.Running}
    }
    return $members
}
function Stop-Generation($members) {
    foreach ($role in @('gateway','ingress','egress','dev')) {
        if ($members[$role] -and $members[$role].running) { Invoke-Engine @('stop',$members[$role].id) | Out-Null }
    }
}
function Restore-Environment {
    $active = if ([IO.File]::Exists($configFile)) { Get-Content -LiteralPath $configFile -Raw | ConvertFrom-Json }
    if (-not $active) { throw '没有可恢复的原环境登记' }
    if ([IO.File]::Exists($pendingFile)) {
        $candidate = Get-Content -Raw -LiteralPath $pendingFile | ConvertFrom-Json
        if ($candidate.owner -ne $active.owner) { Stop-Generation (Get-GenerationMembers $candidate) }
    }
    if ([IO.File]::Exists($handover)) {
        $journal = Get-Content -Raw -LiteralPath $handover | ConvertFrom-Json
        if ($active.owner -eq $journal.original) {
            $members = Get-GenerationMembers $active
            foreach ($role in @('dev','egress','gateway','ingress')) {
                $member = $members[$role]
                $saved = $journal.members.$role
                if ($saved -and $saved.running) {
                    if (-not $member -or $member.id -ne $saved.id) { throw '无法确认原环境身份，已保留切换记录' }
                    if (-not $member.running) { Invoke-Engine @('start',$member.id) | Out-Null }
                }
            }
        } elseif ($active.owner -ne $journal.replacement) { throw '切换记录与当前登记不符' }
    }
    [IO.File]::Delete($pendingFile)
    [IO.File]::Delete($handover)
}
if ($Action -eq 'Recover') { Restore-Environment; return }
if ([IO.File]::Exists($handover)) { throw '上次维护中断，请在项目面板核对环境' }
$names = @(Invoke-Engine @('ps','-a','--format','{{.Names}}'))
if ($names -contains "$rootOwner-dev" -and
        (Invoke-Engine @('inspect','--format','{{.State.Running}}',"$rootOwner-dev")).Trim() -eq 'true') {
    throw '旧共享开发容器仍在运行。请先在旧工作台核对前台执行、保存服务，再停用旧环境。'
}
if ($previous -and ($previous.project -ne $ProjectId -or $previous.parent -ne $projectPath -or $previous.installation -ne $rootOwner)) { throw '原项目环境登记不匹配' }
$targetImages = $catalog.images
$toolFile = Join-Path $dataPath 'tool-images.json'
if ([IO.File]::Exists($toolFile)) {
    $latest = @((Get-Content -Raw -LiteralPath $toolFile | ConvertFrom-Json).releases | Where-Object base -eq $catalog.images.development) | Select-Object -Last 1
    if ($latest) {
        if ($latest.image -cnotmatch '^sha256:[a-f0-9]{64}$') { throw '全局开发镜像登记无效' }
        $targetImages.development = $latest.image
    }
}
if ($Action -eq 'Rollback') {
    if (-not $previous.previousOwner -or $previous.previousOwner -cnotmatch ('^'+[regex]::Escape($namespace)+'-g[0-9a-f]{12}$')) { throw '没有可回退的上一代环境' }
    $generation = Get-Content -Raw -LiteralPath (Join-Path $controlDir ('retired-' + $previous.previousOwner + '.json')) | ConvertFrom-Json
    $members = Get-GenerationMembers $generation
    if (-not $members['dev'] -or $members['dev'].id -ne $generation.containerId) { throw '原代次的开发容器已缺失，已保留当前环境' }
    Save-Registration $pendingFile $generation
} elseif ($previous -and $Action -ne 'Rebuild') {
    $generation = $previous
} elseif (Test-Path -LiteralPath $pendingFile) {
    $generation = Get-Content -Raw -LiteralPath $pendingFile | ConvertFrom-Json
} else {
    $generation = [ordered]@{owner=($namespace+'-g'+[Guid]::NewGuid().ToString('N').Substring(0,12));images=$targetImages}
    Save-Registration $pendingFile $generation
}
$owner = $generation.owner
if ($owner -notmatch ('^'+[regex]::Escape($namespace)+'-g[0-9a-f]{12}$')) { throw '项目代次登记不匹配' }
$label = "dev.delveforge.workbench=$owner"
$extraLabels = @('--label',"dev.delveforge.project=$ProjectId",'--label',"dev.delveforge.installation=$rootOwner")
$images = @{}
foreach ($role in @('development','egress','gateway','edge')) { $images[$role] = $generation.images.$role }
$inside = "$owner-inside"
$outside = "$owner-edge"
$dev = "$owner-dev"
$proxy = "$owner-egress"
$gate = "$owner-gateway"
$edge = "$owner-ingress"
$certPath = Join-Path $controlDir ('tls-' + $owner)
function Assert-Owned([string]$type,[string]$name) {
    if ($type -eq 'container') { $format = '{{json .Config.Labels}}' }
    else { $format = '{{json .Labels}}' }
    $resourceLabels = (Invoke-Engine @($type,'inspect','--format',$format,$name)) | ConvertFrom-Json
    $expected = if ($type -eq 'volume') { $namespace } else { $owner }
    if ($resourceLabels.'dev.delveforge.workbench' -ne $expected -or
            $resourceLabels.'dev.delveforge.project' -ne $ProjectId -or $resourceLabels.'dev.delveforge.installation' -ne $rootOwner) {
        throw "名称已被其他资源占用：$name"
    }
}
# 在停启任何组件前核对整组，避免最后一个镜像过期时留下半启动的环境。
$roles = [ordered]@{development=$dev; egress=$proxy; gateway=$gate; edge=$edge}
$existing = @{}
$outdated = @(foreach ($role in $roles.Keys) {
    $name = $roles[$role]
    if ($names -notcontains $name) {
        if ($role -eq 'development' -and $previous -and $previous.owner -eq $owner) { throw '原开发容器已缺失，请明确选择重建' }
        continue
    }
    $identity = (Invoke-Engine @('container','inspect','--format','{{.Id}} {{.Image}}',$name)).Trim().Split(' ')
    Assert-Owned 'container' $identity[0]
    if ($role -eq 'development' -and $previous -and $previous.owner -eq $owner -and $identity[0] -ne $previous.containerId) { throw '开发容器已被替换，停止操作' }
    $existing[$name] = $identity[0]
    if ($Action -ne 'Rebuild') {
        $expected = (Invoke-Engine @('image','inspect','--format','{{.Id}}',$images[$role])).Trim()
        if ($identity[1] -ne $expected) { $role }
    }
})
if ($outdated.Count -gt 0) { throw '已登记镜像与容器不符，请检查环境后显式升级。' }
foreach ($path in @($dataPath,$projectPath,$certPath)) {
    [IO.Directory]::CreateDirectory($path) | Out-Null
}
$networks = @(Invoke-Engine @('network','ls','--format','{{.Name}}'))
foreach ($name in @($inside,$outside)) {
    if ($networks -contains $name) { Assert-Owned 'network' $name }
    else {
        $netArgs = @('network','create','--label',$label) + $extraLabels
        if ($name -eq $inside) { $netArgs += @('--internal','--opt','com.docker.network.bridge.gateway_mode_ipv4=isolated') }
        # Docker Desktop 默认自动分配的大子网很快耗尽。每组只有少数端点，使用小型独立子网。
        $seed = [Convert]::ToUInt32((Get-Digest $name).Substring(0,4),16)
        $allocated = $false
        for ($attempt = 0; $attempt -lt 64; $attempt++) {
            $slot = ($seed + $attempt) % 65536
            $subnet = '10.{0}.{1}.{2}/28' -f (240 + ($slot -shr 12)),(($slot -shr 4) -band 255),(($slot -band 15) * 16)
            $createArgs = $netArgs + @('--subnet',$subnet,$name)
            $beforeError = $ErrorActionPreference
            try { $ErrorActionPreference = 'Continue'; $answer = & docker @createArgs 2>&1 }
            finally { $ErrorActionPreference = $beforeError }
            if ($LASTEXITCODE -eq 0) { $allocated = $true; break }
            if (($answer | Out-String) -notmatch 'overlap') { throw "创建项目网络失败：$answer" }
        }
        if (-not $allocated) { throw '没有可用的项目子网，请检查 Docker 网络配置。' }
    }
}
if (@(Invoke-Engine @('volume','ls','--format','{{.Name}}')) -contains $stateVolume) { Assert-Owned 'volume' $stateVolume }
else { Invoke-Engine (@('volume','create','--label',"dev.delveforge.workbench=$namespace") + $extraLabels + $stateVolume) | Out-Null }
# 旧卷只交给一次性的离线复制器。只复制这个项目的目录，整个共享缓存保留在旧卷。
$legacyFile = Join-Path $dataPath 'environment.json'
$migrationFile = Join-Path $controlDir 'legacy-data.json'
if (-not $previous -and [IO.File]::Exists($legacyFile) -and -not [IO.File]::Exists($migrationFile)) {
    $legacy = Get-Content -Raw -LiteralPath $legacyFile | ConvertFrom-Json
    if ($legacy.owner -ne $rootOwner -or $legacy.container -ne "$rootOwner-dev" -or
            [IO.Path]::GetFullPath($legacy.parent) -ne (Join-Path $dataPath 'projects')) { throw '旧环境的归属待核对' }
    $oldVolume = "$rootOwner-state"
    $oldLabels = (Invoke-Engine @('volume','inspect','--format','{{json .Labels}}',$oldVolume)) | ConvertFrom-Json
    if ($oldLabels.'dev.delveforge.workbench' -ne $rootOwner) { throw '旧数据卷的归属待核对' }
    $copyLegacy = @'
set -eu
test ! -L /old/projects
src=/old/projects/$PROJECT_ID
dst=/new/projects/$PROJECT_ID
test ! -L "$src"
if ! test -d "$src"; then echo empty; exit 0; fi
mkdir -p /new/projects
test ! -L /new/projects
if test -d "$dst"; then echo preserved; exit 0; fi
tmp=$(mktemp -d /new/projects/.migration.XXXXXX)
trap 'rm -rf "$tmp"' EXIT
cp -a "$src/." "$tmp/"
mv -T "$tmp" "$dst"
echo copied
'@
    $result = $copyLegacy -replace "`r","" | & docker run --rm -i --network none --cap-drop ALL --cap-add CHOWN --cap-add DAC_OVERRIDE --cap-add FOWNER `
        --mount "type=volume,src=$oldVolume,target=/old,readonly" --mount "type=volume,src=$stateVolume,target=/new" `
        -e "PROJECT_ID=$ProjectId" --entrypoint /bin/sh $images.development -es
    if ($LASTEXITCODE -ne 0 -or $result -notin @('empty','preserved','copied')) { throw '旧项目数据迁移未完成，原卷已保留' }
    Save-Registration $migrationFile ([ordered]@{project=$ProjectId;source=$oldVolume;result=$result})
}
if (-not (Test-Path -LiteralPath (Join-Path $certPath 'client/client.key'))) {
    Invoke-Engine @('run','--rm','--network','none','--cap-drop','ALL','--user','0:0',
        '--mount',"type=bind,src=$certPath,target=/keys",'--entrypoint','/bin/sh',$images.edge,'/certs.sh') | Out-Null
}
$gatewayCert = Join-Path $certPath 'gateway'
$hostRules = Join-Path $controlDir 'egress'
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
if ($Action -eq 'Restart') {
    Write-Host '重启会终止该环境后台服务，保留已安装的软件。'
    foreach ($name in @($gate,$edge,$proxy,$dev)) {
        if ($existing.ContainsKey($name)) { Invoke-Engine @('stop',$existing[$name]) | Out-Null }
    }
}
function Start-Owned([string]$name,[string]$image,[string[]]$argsForCreate,[bool]$external) {
    $id = $existing[$name]
    if (-not $id) {
        $create = @('create','--name',$name,'--label',$label,'--security-opt','no-new-privileges') + $extraLabels + $argsForCreate + $image
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
    '--cap-add','SYS_CHROOT','--mount',"type=bind,src=$projectPath,target=$projectMount",
    '--mount',"type=volume,src=$stateVolume,target=/state",'--pids-limit',"$PidLimit",'--cpus',"$CpuLimit",'--memory',"${MemoryMiB}m")
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
$identity = (Invoke-Engine @('inspect','--format','{{.Id}} {{.Image}} {{.State.StartedAt}}',$dev)).Trim().Split(' ')
$info = [ordered]@{owner=$owner; installation=$rootOwner; project=$ProjectId; endpoint=$endpoint;
    container=$dev; parent=$projectPath; containerId=$identity[0]; image=$identity[1]; startedAt=$identity[2];
    stateVolume=$stateVolume; images=$images; previewPort=(Get-Port 8140); controlPort=(Get-Port 8141)}
if ($previous -and $previous.owner -ne $owner) { $info.previousOwner = $previous.owner }
elseif ($previous.previousOwner) { $info.previousOwner = $previous.previousOwner }
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
if ($previous -and $previous.owner -ne $owner) {
    $originalMembers = Get-GenerationMembers $previous
    Save-Registration $handover ([ordered]@{original=$previous.owner;replacement=$owner;members=$originalMembers})
    Save-Registration (Join-Path $controlDir ('retired-' + $previous.owner + '.json')) $previous
    Stop-Generation $originalMembers
}
Save-Registration $configFile $info
[IO.File]::Delete($pendingFile)
[IO.File]::Delete($handover)
Write-Host "项目工作副本 $projectPath"
} catch {
    if ($Action -in @('Rebuild','Rollback') -and $previous) { Restore-Environment }
    throw
} finally { $guard.Dispose() }
