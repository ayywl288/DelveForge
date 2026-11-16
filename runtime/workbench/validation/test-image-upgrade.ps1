[CmdletBinding()]
param([string]$TemporaryParent = [IO.Path]::GetTempPath())

$ErrorActionPreference = 'Stop'
$parent = (Resolve-Path -LiteralPath $TemporaryParent).ProviderPath
$leaf = 'df-upgrade-' + [Guid]::NewGuid().ToString('N')
$root = [IO.Path]::Combine($parent, $leaf)
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $digest = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToLowerInvariant()))
    $owner = 'df-wb-' + [BitConverter]::ToString($digest).Replace('-', '').Substring(0,12).ToLowerInvariant()
} finally { $sha.Dispose() }
$label = "dev.delveforge.workbench=$owner"

function Invoke-TestDocker([string[]]$Arguments) {
    $result = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker $($Arguments[0]) failed." }
    return $result
}

# 只在本测试独占的临时目录中模拟落后的最后一个组件。
$development = @(Invoke-TestDocker @('image','ls','--filter','reference=df-workbench/development:*',
        '--filter','label=dev.delveforge.workbench.image=true','-q')) | Select-Object -First 1
if (-not $development) { throw 'Run the Workbench setup once to build its development image.' }
[IO.Directory]::CreateDirectory($root) | Out-Null
try {
    $marker = Invoke-TestDocker @('create','--name',"$owner-ingress",'--label',$label,
            '--network','none',$development)
    foreach ($action in @('Ensure','Restart','Rebuild')) {
        $failure = $null
        try {
            & (Join-Path $PSScriptRoot '../Initialize-Environment.ps1') -DataDirectory $root -Action $action
        } catch { $failure = $_.Exception }
        if (($action -eq 'Ensure' -and $failure) -or ($action -ne 'Ensure' -and -not $failure)) {
            throw "$action failed to protect the original environment: $failure"
        }
        $members = @(Invoke-TestDocker @('ps','-aq','--no-trunc','--filter',"label=$label"))
        $status = Invoke-TestDocker @('inspect','--format','{{.State.Status}}',$marker)
        $networks = @(Invoke-TestDocker @('network','ls','-q','--filter',"label=$label"))
        $volumes = @(Invoke-TestDocker @('volume','ls','-q','--filter',"label=$label"))
        if ($members.Count -ne 1 -or $members[0] -ne $marker -or $status -ne 'created' -or
                $networks.Count -ne 0 -or $volumes.Count -ne 0 -or
                -not (Test-Path -LiteralPath (Join-Path $root 'runtime-catalog.json'))) {
            throw "$action changed the environment before reporting the image mismatch."
        }
        Write-Host "PASS $action"
    }
} finally {
    # 临时环境的标签由本次随机目录生成；不接触其他环境的资源。
    foreach ($id in @(Invoke-TestDocker @('ps','-aq','--filter',"label=$label"))) {
        Invoke-TestDocker @('rm','-f',$id) | Out-Null
    }
    foreach ($id in @(Invoke-TestDocker @('network','ls','-q','--filter',"label=$label"))) {
        Invoke-TestDocker @('network','rm',$id) | Out-Null
    }
    foreach ($name in @(Invoke-TestDocker @('volume','ls','-q','--filter',"label=$label"))) {
        Invoke-TestDocker @('volume','rm',$name) | Out-Null
    }
    $created = (Resolve-Path -LiteralPath $root).ProviderPath
    if ([IO.Path]::GetDirectoryName($created) -eq $parent.TrimEnd('\') -and
            [IO.Path]::GetFileName($created) -eq $leaf) {
        Remove-Item -LiteralPath $created -Recurse -Force
    }
}
