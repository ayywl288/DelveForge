# 由初始化脚本加载。共享缓存只读代理固定的 Maven Central 来源，独占一份下载卷。
function Enter-CacheLock([string]$directory) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $until = [DateTime]::UtcNow.AddSeconds(60)
    do {
        try { return [IO.File]::Open((Join-Path $directory 'cache.lock'),'OpenOrCreate','ReadWrite','None') }
        catch [IO.IOException] { if ([DateTime]::UtcNow -ge $until) { throw }; Start-Sleep -Milliseconds 100 }
    } while ($true)
}
function Confirm-CacheOwner([string]$type,[string]$id) {
    $query = if ($type -eq 'container') { '{{json .Config.Labels}}' } else { '{{json .Labels}}' }
    $labels = (Invoke-Engine @($type,'inspect','--format',$query,$id)) | ConvertFrom-Json
    if ($labels.'dev.delveforge.cache' -cne $rootOwner -or $labels.'dev.delveforge.installation' -cne $rootOwner) {
        throw '下载缓存资源归属不符'
    }
}
function Connect-DownloadCache([string]$inside,[string]$ruleText) {
    $directory = Join-Path $dataPath 'download-cache'
    $lock = Enter-CacheLock $directory
    try {
        $name = "$rootOwner-central"
        $volume = "$name-downloads"
        $external = "$name-edge"
        $labels = @('--label',"dev.delveforge.cache=$rootOwner",'--label',"dev.delveforge.installation=$rootOwner")
        $file = Join-Path $directory 'cache.json'
        $registered = if ([IO.File]::Exists($file)) { Get-Content -Raw -LiteralPath $file | ConvertFrom-Json }
        $rulesDir = Join-Path $directory 'egress'
        [IO.Directory]::CreateDirectory($rulesDir) | Out-Null
        [IO.File]::WriteAllText((Join-Path $rulesDir 'addresses.nft'),$ruleText,[Text.Encoding]::ASCII)
        if (@(Invoke-Engine @('network','ls','--format','{{.Name}}')) -contains $external) {
            Confirm-CacheOwner network $external
        } else { New-SizedNetwork $external (@('network','create') + $labels) }
        if (@(Invoke-Engine @('volume','ls','--format','{{.Name}}')) -contains $volume) {
            Confirm-CacheOwner volume $volume
        } else { Invoke-Engine (@('volume','create') + $labels + $volume) | Out-Null }
        if (@(Invoke-Engine @('ps','-a','--format','{{.Names}}')) -contains $name) {
            $id = (Invoke-Engine @('inspect','--format','{{.Id}}',$name)).Trim()
            Confirm-CacheOwner container $id
            if ($registered -and $registered.id -ne $id) { throw '下载缓存容器身份与登记不符' }
        } else {
            $argsForCreate = @('create','--name',$name,'--network',$external,'--sysctl','net.ipv4.ip_forward=0',
                '--cap-drop','ALL','--cap-add','NET_ADMIN','--cap-add','SETUID','--cap-add','SETGID','--cap-add','CHOWN',
                '--cap-add','DAC_OVERRIDE','--cap-add','NET_BIND_SERVICE','--security-opt','no-new-privileges',
                '--pids-limit','96','--memory','256m','--log-opt','max-size=4m','--log-opt','max-file=2',
                '--mount',"type=volume,src=$volume,target=/cache",'--mount',"type=bind,src=$rulesDir,target=/host,readonly")
            $id = (Invoke-Engine ($argsForCreate + $labels + $catalog.images.cache)).Trim()
        }
        $resource = @(Invoke-Engine @('container','inspect',$id) | Out-String | ConvertFrom-Json)[0]
        $expectedImage = if ($registered -and $registered.id -ceq $id) { $registered.image } else { $catalog.images.cache }
        $backing = @($resource.Mounts | Where-Object {
            $_.Type -ceq 'volume' -and $_.Name -ceq $volume -and $_.Destination -ceq '/cache' -and $_.RW
        })
        if ($resource.Image -cne $expectedImage -or $backing.Count -ne 1 -or $resource.HostConfig.NetworkMode -cne $external) {
            throw '下载缓存的镜像、下载卷或出口网络与登记不符'
        }
        $attached = $resource.NetworkSettings.Networks
        if ($null -eq $attached.PSObject.Properties[$inside]) {
            Invoke-Engine @('network','connect','--gw-priority','-1','--alias','maven-cache',$inside,$id) | Out-Null
        }
        if ((Invoke-Engine @('inspect','--format','{{.State.Running}}',$id)).Trim() -ne 'true') {
            Invoke-Engine @('start',$id) | Out-Null
        }
        $loaded = $false
        for ($i = 0; $i -lt 10; $i++) {
            & docker exec $id nft -f /host/addresses.nft 2>$null
            if ($LASTEXITCODE -eq 0) { $loaded=$true; break }
            Start-Sleep -Milliseconds 200
        }
        if (-not $loaded) { throw '下载缓存的出口限制未就绪' }
        Save-Registration $file ([ordered]@{id=$id;image=$resource.Image;owner=$rootOwner;volume=$volume;name=$name})
        return $id
    } finally { $lock.Dispose() }
}
