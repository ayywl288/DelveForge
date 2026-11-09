#requires -Version 5.1

function Invoke-DfDocker {
    param([string] $Docker, [string[]] $Arguments)
    $ErrorActionPreference = 'Continue'
    $output = & $Docker @Arguments 2>&1
    return @{ Code = $LASTEXITCODE; Output = ($output | Out-String).Trim() }
}

function Get-DfDockerExecutable {
    $command = Get-Command 'docker' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    return $null
}

function Get-DfAgentInputs {
    param([string] $Root)
    $context = Join-Path $Root 'backend/delveforge-infrastructure/src/main/resources/agent'
    foreach ($name in @('Dockerfile', 'sandbox.py', 'command.py', 'test_reports.py', 'proxy.py', 'offline-settings.xml')) {
        if (-not (Test-Path -LiteralPath (Join-Path $context $name) -PathType Leaf)) {
            Stop-DfSetup "Agent build input missing: $name. Restore the trusted checkout."
        }
    }
    $entries = @(Get-ChildItem -LiteralPath $context -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($context.Length).Replace('\', '/') + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
    $baseImages = @{ 21 = 'maven:3.9.9-eclipse-temurin-21'; 17 = 'maven:3.9.9-eclipse-temurin-17' }
    foreach ($jdk in @(21, 17)) { $entries += "BASE_IMAGE_${jdk}:$($baseImages[$jdk])" }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $digest = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($entries -join "`n")))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
    return @{ Context = $context; Digest = $digest; BaseImages = $baseImages }
}

function Assert-DfAgentImage {
    param([string] $Docker, [string] $Image, [int] $Jdk, [string] $Context)
    $inspection = Invoke-DfDocker $Docker @('image', 'inspect', '--format', '{{.Id}}', $Image)
    if ($inspection.Code -ne 0 -or $inspection.Output -notmatch '^sha256:[0-9a-f]{64}$') {
        Stop-DfSetup "Cannot resolve Agent JDK $Jdk image. Check Docker and the explicit image override."
    }
    $imageId = $inspection.Output
    # 核验固定 ID 的真实工具内容及解释器握手；标签或 label 不能代替内容证明。
    $probe = @'
import hashlib,json,pathlib,subprocess
p=subprocess.run(['python3','/opt/delveforge/sandbox.py'],input=json.dumps(dict(name='protocol',arguments={})).encode(),stdout=subprocess.PIPE,check=True)
r=json.loads(p.stdout)
print(json.dumps({'handshake':r,'pnpm8':subprocess.run(['/opt/pnpm8/bin/pnpm','--version'],stdout=subprocess.PIPE,check=True).stdout.decode().strip(),'hashes':{n:hashlib.sha256(pathlib.Path('/opt/delveforge',n).read_bytes()).hexdigest() for n in ['sandbox.py','command.py', 'test_reports.py','proxy.py','offline-settings.xml']},'java':subprocess.run(['java','-version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout.decode(),'tools':{n:subprocess.run([n,'--version'],stdout=subprocess.PIPE,check=True).stdout.decode().strip() for n in ['node','npm','pnpm']}}))
'@
    $result = Invoke-DfDocker $Docker @('run', '--rm', '--network=none', '--read-only', '--cap-drop=ALL',
        '--security-opt=no-new-privileges', '--pids-limit=64', '--memory=256m', '--cpus=1',
        '--label', 'com.delveforge.bootstrap=protocol-v10', $imageId, 'python3', '-c', $probe)
    if ($result.Code -ne 0) { Stop-DfSetup "Agent JDK $Jdk image probe failed. Rebuild the current trusted context using a new identity." }
    try { $data = $result.Output | ConvertFrom-Json } catch { Stop-DfSetup "Agent JDK $Jdk image returned an invalid probe." }
    $handshake = $data.handshake
    if ($handshake.ok -isnot [bool] -or $handshake.ok -cne $true -or $handshake.tool -cne 'protocol' -or
            $handshake.result.protocol -cne 'delveforge-sandbox' -or
            ($handshake.result.version -isnot [long] -and $handshake.result.version -isnot [int]) -or
            $handshake.result.version -ne 10 -or $data.java -notmatch ('version "' + $Jdk + '\.') -or
            $data.tools.node -cne 'v22.16.0' -or $data.tools.npm -cne '10.9.2' -or $data.tools.pnpm -cne '12.9.1' -or $data.pnpm8 -cne '8.15.9') {
        Stop-DfSetup "Agent JDK $Jdk image is incompatible with protocol v10 or the selected JDK. Use a new current image."
    }
    foreach ($name in @('sandbox.py', 'command.py', 'test_reports.py', 'proxy.py', 'offline-settings.xml')) {
        if ($data.hashes.$name -cne (Get-FileHash -LiteralPath (Join-Path $Context $name) -Algorithm SHA256).Hash.ToLowerInvariant()) {
            Stop-DfSetup "Agent JDK $Jdk image has stale tool contents. Build the current context with a new identity."
        }
    }
    Write-Host "Verified Agent JDK ${Jdk}: $imageId (protocol v10)."
    return $imageId
}

function Initialize-DfAgentImages {
    param([string] $Root, [hashtable] $Configuration, [switch] $DisableAgent,
        [string] $Image21, [string] $Image17)
    if ($DisableAgent) {
        $Configuration.DELVEFORGE_AGENT_ENABLED = 'false'
        Write-Host 'Agent explicitly disabled; discovery and planning remain available.'
        return
    }
    $docker = Get-DfDockerExecutable
    if (-not $docker) {
        Stop-DfSetup 'Docker is missing. Install and start a Linux Docker Engine, or rerun with -DisableAgent for discovery only.'
    }
    $engine = Invoke-DfDocker $docker @('version', '--format', '{{.Server.Os}} {{.Server.APIVersion}}')
    if ($engine.Code -ne 0) { Stop-DfSetup 'Docker is unreachable. Start Docker and check access, or rerun with -DisableAgent for discovery only.' }
    if ($engine.Output -notmatch '^linux (\d+\.\d+)$' -or [version]$Matches[1] -lt [version]'1.48') {
        Stop-DfSetup 'Agent requires a Linux Docker Engine API 1.48 or newer. Select a compatible Engine, or use -DisableAgent.'
    }
    $inputs = Get-DfAgentInputs $Root
    foreach ($jdk in @(21, 17)) {
        $override = $Image21
        if ($jdk -eq 17) { $override = $Image17 }
        $tag = $override
        if (-not $tag) {
            $tag = "delveforge-agent:protocol-v10-jdk$jdk-$($inputs.Digest)"
            $existing = Invoke-DfDocker $docker @('image', 'ls', '--quiet', '--no-trunc', '--filter', "reference=$tag")
            if ($existing.Code -ne 0) { Stop-DfSetup 'Cannot list Docker images. Check Engine availability.' }
            if (-not $existing.Output) {
                Write-Host "Building Agent JDK $jdk for current source inputs (first launch may take several minutes)..."
                $build = Invoke-DfDocker $docker @('build', '--tag', $tag, '--build-arg', "BASE_IMAGE=$($inputs.BaseImages[$jdk])",
                    '--label', "com.delveforge.agent.inputs=$($inputs.Digest)", $inputs.Context)
                if ($build.Code -ne 0) { Stop-DfSetup "Agent JDK $jdk image build failed. Check Docker build access to Docker Hub and Debian mirrors; rerun after fixing connectivity." }
            } else { Write-Host "Reusing Agent JDK $jdk image for unchanged inputs." }
        }
        $Configuration["DELVEFORGE_AGENT_IMAGE_$jdk"] = Assert-DfAgentImage $docker $tag $jdk $inputs.Context
    }
    $Configuration.DELVEFORGE_AGENT_ENABLED = 'true'
}
