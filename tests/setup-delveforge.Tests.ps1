#requires -Version 5.1
# 无额外测试框架；使用假密钥、隔离文件和端口，不调用 Provider。
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $repoRoot 'setup-delveforge.ps1')
$passed = 0
$failed = 0
$fixture = Join-Path $repoRoot ("backend/delveforge-app/target/launcher-tests/Space ' & [x] `$ __JAVA__ __PNPM__ root-" + [guid]::NewGuid())
$null = [IO.Directory]::CreateDirectory((Join-Path $fixture 'frontend'))
$null = [IO.Directory]::CreateDirectory((Join-Path $fixture 'backend/delveforge-app/target'))
[IO.File]::WriteAllText((Join-Path $fixture 'pom.xml'), '<project><version>0.1.0-SNAPSHOT</version></project>')
[IO.File]::WriteAllText((Join-Path $fixture 'backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar'), 'fixture')
$originals = @{}
foreach ($name in @('Read-Host', 'Get-DfDependencies', 'Invoke-DfBuild', 'Wait-DfConnectivity',
        'Assert-DfPortsFree', 'Start-Process', 'Get-CimInstance', 'Get-DfListenerOwners',
        'Test-DfProcessOwner', 'Invoke-RestMethod', 'Get-DfCommand', 'Get-DfVersion',
        'Initialize-DfAgentImages', 'Invoke-DfDocker', 'Get-DfDockerExecutable')) {
    $originals[$name] = (Get-Command $name).ScriptBlock
}

function Assert-True {
    param([bool] $Condition, [string] $Message = 'Assertion failed')
    if (-not $Condition) {
        throw $Message
    }
}

function Assert-Rejected {
    param([scriptblock] $Action, [string] $Expected)
    $rejected = $false
    try {
        & $Action
    } catch {
        $rejected = $_.Exception.Data['DelveForgeSetup'] -and $_.Exception.Message -like $Expected
    }
    Assert-True $rejected 'Expected a specific safe rejection'
}

function Test-Case {
    param([string] $Name, [scriptblock] $Action)
    try {
        & $Action
        $script:passed++
        Write-Host "PASS: $Name"
    } catch {
        $script:failed++
        Write-Host "FAIL: $Name ($($_.Exception.GetType().Name))"
    } finally {
        foreach ($name in $originals.Keys) {
            if ($originals[$name]) {
                Set-Item "Function:script:$name" $originals[$name]
            } else {
                Remove-Item "Function:script:$name" -ErrorAction SilentlyContinue
            }
        }
    }
}

function Set-Answers {
    param([string[]] $Values)
    $script:answers = New-Object 'System.Collections.Generic.Queue[string]'
    foreach ($value in $Values) {
        $script:answers.Enqueue($value)
    }
    $script:hiddenInput = $false
    Set-Item Function:script:Read-Host {
        param([string] $Prompt, [switch] $AsSecureString)
        $value = $script:answers.Dequeue()
        if ($AsSecureString) {
            $script:hiddenInput = $true
            $result = New-Object Security.SecureString
            foreach ($character in $value.ToCharArray()) {
                $result.AppendChar($character)
            }
            return $result
        }
        return $value
    }
}

function Set-StartupDoubles {
    $script:launches = @()
    $script:checkedPorts = @()
    $script:builds = 0
    Set-Item Function:script:Initialize-DfAgentImages {
        param($Root, $Configuration)
        Assert-True (-not $env:DEEPSEEK_API_KEY) 'Docker builds must not inherit a key'
        $Configuration.DELVEFORGE_AGENT_IMAGE_21 = 'sha256:' + ('a' * 64)
        $Configuration.DELVEFORGE_AGENT_IMAGE_17 = 'sha256:' + ('b' * 64)
        $Configuration.DELVEFORGE_AGENT_ENABLED = 'true'
    }
    Set-Item Function:script:Get-DfDependencies {
        return @{ Java = 'C:\JDK with spaces\bin\java.exe'; JavaHome = 'C:\JDK with spaces'; Pnpm = 'C:\Node with spaces\pnpm.cmd' }
    }
    Set-Item Function:script:Invoke-DfBuild {
        Assert-True (-not $env:DEEPSEEK_API_KEY) 'Build/install must not inherit a key'
        $script:builds++
    }
    Set-Item Function:script:Assert-DfPortsFree {}
    Set-Item Function:script:Wait-DfConnectivity {
        param([int] $Port, $Terminal)
        $script:checkedPorts += $Port
    }
    # 保留真实的环境隔离及编码命令生成，仅替换新终端的 OS 启动边界。
    Set-Item Function:script:Start-Process {
        param($FilePath, $WorkingDirectory, $ArgumentList, $WindowStyle, [switch] $PassThru)
        $command = [Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($ArgumentList[-1]))
        $script:launches += @{
            Command = $command; Root = $WorkingDirectory; Key = $env:DEEPSEEK_API_KEY
            Port = $env:SERVER_PORT; Proxy = $env:BACKEND_DEV_URL; Model = $env:DELVEFORGE_AI_DEEPSEEK_MODEL
            Database = $env:DELVEFORGE_PERSISTENCE_DATABASE_FILE; Existing = $env:DELVEFORGE_WORKSPACE_EXISTING_ROOTS
            Workspace = $env:DELVEFORGE_WORKSPACE_ROOT
            Agent21 = $env:DELVEFORGE_AGENT_IMAGE_21; Agent17 = $env:DELVEFORGE_AGENT_IMAGE_17
            AgentEnabled = $env:DELVEFORGE_AGENT_ENABLED
            Profile = $env:SPRING_PROFILES_ACTIVE
            KeyPresent = [Environment]::GetEnvironmentVariables('Process').Contains('DEEPSEEK_API_KEY')
            ModelPresent = [Environment]::GetEnvironmentVariables('Process').Contains('DELVEFORGE_AI_DEEPSEEK_MODEL')
        }
        return [Diagnostics.Process]::GetCurrentProcess()
    }
}

Test-Case 'real installed dependency versions' {
    $dependencies = Get-DfDependencies $repoRoot
    Assert-True (Test-Path -LiteralPath $dependencies.Java)
}

foreach ($inputValue in @('', '   ')) {
    Test-Case 'empty/whitespace key rejected with masked input' {
        Set-Answers @($inputValue)
        Assert-Rejected { Read-DfKey } 'DeepSeek API Key is required*'
        Assert-True $script:hiddenInput
    }
}

Test-Case 'default ignores inherited overrides, isolates key, preserves database and caller' {
    Set-StartupDoubles
    Set-Answers @('launcher-test-canary-not-a-provider-key', '')
    $names = @('DEEPSEEK_API_KEY', 'SERVER_PORT', 'BACKEND_DEV_URL', 'DELVEFORGE_AI_DEEPSEEK_MODEL',
        'DELVEFORGE_AGENT_IMAGE_21', 'DELVEFORGE_AGENT_IMAGE_17', 'DELVEFORGE_AGENT_ENABLED',
        'DELVEFORGE_PERSISTENCE_DATABASE_FILE', 'DELVEFORGE_WORKSPACE_ROOT',
        'SPRING_PROFILES_ACTIVE', 'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS')
    $saved = @{}
    foreach ($name in $names) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, 'inherited-test-canary', 'Process')
    }
    $db = Join-Path $fixture 'data/delveforge.db'
    $null = [IO.Directory]::CreateDirectory((Split-Path $db -Parent))
    [IO.File]::WriteAllText($db, 'existing-database-canary')
    $before = (Get-FileHash -LiteralPath $db).Hash
    $location = (Get-Location).Path
    try {
        Invoke-DfSetup $fixture
        Assert-True ($script:launches.Count -eq 2)
        Assert-True ($script:launches[0].Key -eq 'launcher-test-canary-not-a-provider-key')
        Assert-True (-not $script:launches[1].Key)
        Assert-True (-not $script:launches[1].KeyPresent -and -not $script:launches[0].ModelPresent)
        Assert-True (-not $script:launches[0].Port -and -not $script:launches[0].Model)
        Assert-True (-not $script:launches[0].Database)
        Assert-True ($script:launches[0].Agent21 -eq ('sha256:' + ('a' * 64)))
        Assert-True ($script:launches[0].Agent17 -eq ('sha256:' + ('b' * 64)))
        Assert-True ($script:launches[0].AgentEnabled -eq 'true')
        Assert-True (-not $script:launches[1].Agent21 -and -not $script:launches[1].Agent17)
        Assert-True ($script:launches[0].Workspace -eq [IO.Path]::GetFullPath((Join-Path $fixture 'data/workspaces')))
        Assert-True (-not $script:launches[1].Workspace)
        Assert-True (-not $script:launches[0].Profile)
        Assert-True ($script:launches[0].Command -notmatch 'spring.profiles.active')
        Assert-True ($script:launches[1].Proxy -eq 'http://127.0.0.1:8080')
        Assert-True (($script:checkedPorts -join ',') -eq '8080,5173')
        Assert-True ((Get-FileHash -LiteralPath $db).Hash -eq $before)
        Assert-True ((Get-Location).Path -eq $location)
        foreach ($launch in $script:launches) {
            Assert-True ($launch.Command -notmatch 'launcher-test-canary|DEEPSEEK_API_KEY\s*=')
            Assert-True ($launch.Command.Contains((ConvertTo-DfLiteral $fixture)) -or
                $launch.Command.Contains((ConvertTo-DfLiteral (Join-Path $fixture 'frontend'))))
            $tokens = $null; $errors = $null
            $null = [Management.Automation.Language.Parser]::ParseInput($launch.Command, [ref] $tokens, [ref] $errors)
            Assert-True ($errors.Count -eq 0) 'Special-character path must not become command syntax'
        }
        foreach ($name in $names) {
            Assert-True ([Environment]::GetEnvironmentVariable($name, 'Process') -eq 'inherited-test-canary')
        }
    } finally {
        foreach ($name in $saved.Keys) {
            [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
        }
    }
}

Test-Case 'custom port/model/paths and proxy agree' {
    Set-StartupDoubles
    Set-Answers @('launcher-test-canary-not-a-provider-key', 'y', '18080', 'test-model', 'http://127.0.0.1:9',
        (Join-Path $fixture 'custom db.sqlite'), 'C:\Workspace with spaces & $data', 'C:\Logs with spaces', 'C:\Old one,C:\Old two')
    Invoke-DfSetup $fixture
    Assert-True ($script:launches[0].Port -eq '18080' -and $script:launches[0].Model -eq 'test-model')
    Assert-True ($script:launches[0].Existing -eq 'C:\Old one,C:\Old two')
    Assert-True ($script:launches[0].Workspace -eq 'C:\Workspace with spaces & $data')
    Assert-True ($script:launches[1].Proxy -eq 'http://127.0.0.1:18080' -and -not $script:launches[1].Key)
    Assert-True (($script:checkedPorts -join ',') -eq '18080,5173')
}

Test-Case 'custom blanks use defaults' {
    Set-Answers @('y', '', '', '', '', '', '', '')
    Assert-True ((Read-DfConfiguration).Count -eq 0)
}

Test-Case 'null environment value removes a variable, not an empty override' {
    Set-DfEnvironment 'DELVEFORGE_LAUNCHER_TEST' 'canary'
    Set-DfEnvironment 'DELVEFORGE_LAUNCHER_TEST' $null
    Assert-True (-not [Environment]::GetEnvironmentVariables('Process').Contains('DELVEFORGE_LAUNCHER_TEST'))
}

Test-Case 'invalid port safely rejected' {
    Set-Answers @('y', '8080; exit', '', '', '', '', '', '')
    Assert-Rejected { Read-DfConfiguration } 'Backend port must*'
}

Test-Case 'URL credentials rejected without echo' {
    Set-Answers @('y', '', '', 'https://user:canary@example.invalid', '', '', '', '')
    Assert-Rejected { Read-DfConfiguration } 'DeepSeek base URL must*'
}

Test-Case 'relative WorkingCopy root rejected' {
    Set-Answers @('y', '', '', '', '', '\relative', '', '')
    Assert-Rejected { Read-DfConfiguration } 'WorkingCopy roots must*'
}

Test-Case 'literal path round-trip resists command injection' {
    $value = "C:\space ' ; & [x] `$ root"
    $result = & ([scriptblock]::Create((ConvertTo-DfLiteral $value)))
    Assert-True ($result -ceq $value)
}

Test-Case 'occupied port and repeated run rejected without touching listener' {
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        $port = $listener.LocalEndpoint.Port
        Assert-Rejected { Assert-DfPortsFree $port } 'Port * is occupied*'
        Assert-Rejected { Assert-DfPortsFree $port } 'Port * is occupied*'
        Assert-True $listener.Server.IsBound
    } finally {
        $listener.Stop()
    }
}

Test-Case 'unavailable database rejected without mutation' {
    $db = Join-Path $fixture 'locked.sqlite'
    [IO.File]::WriteAllText($db, 'locked-canary')
    $handle = [IO.File]::Open($db, 'Open', 'Read', 'None')
    try {
        Assert-Rejected { Assert-DfDatabaseAvailable $fixture @{ DELVEFORGE_PERSISTENCE_DATABASE_FILE = $db } } 'The selected database*'
    } finally {
        $handle.Dispose()
    }
    Assert-True ([IO.File]::ReadAllText($db) -eq 'locked-canary')
}

Test-Case 'default preflight checks the application default database' {
    $db = Join-Path $fixture 'data/delveforge.db'
    $handle = [IO.File]::Open($db, 'Open', 'Read', 'None')
    try {
        Assert-Rejected { Assert-DfDatabaseAvailable $fixture @{} } 'The selected database*'
    } finally {
        $handle.Dispose()
    }
    Assert-True ([IO.File]::ReadAllText($db) -eq 'existing-database-canary')
}

Test-Case 'build failure starts nothing and restores environment' {
    Set-StartupDoubles
    Set-Answers @('launcher-test-canary-not-a-provider-key', 'N')
    Set-Item Function:script:Invoke-DfBuild { Stop-DfSetup 'Backend build failed.' }
    $before = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process')
    Assert-Rejected { Invoke-DfSetup $fixture } 'Backend build failed*'
    Assert-True ($script:launches.Count -eq 0)
    Assert-True ([Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'Process') -eq $before)
}

Test-Case 'missing dependency exits direct entrypoint before prompting or building' {
    $savedPath = $env:PATH
    $savedJava = $env:JAVA_HOME
    try {
        $env:PATH = ''
        $env:JAVA_HOME = $null
        Push-Location -LiteralPath $repoRoot
        try {
            .\setup-delveforge.ps1
            Assert-True ($LASTEXITCODE -eq 1)
        } finally {
            Pop-Location
        }
    } finally {
        $env:PATH = $savedPath
        $env:JAVA_HOME = $savedJava
    }
}

Test-Case 'process ancestry accepts own child and rejects an unrelated PID' {
    Set-Item Function:script:Get-CimInstance {
        @([pscustomobject]@{ ProcessId = 20; ParentProcessId = 10 },
            [pscustomobject]@{ ProcessId = 30; ParentProcessId = 20 },
            [pscustomobject]@{ ProcessId = 99; ParentProcessId = 1 })
    }
    Assert-True (Test-DfProcessOwner 30 10)
    Assert-True (-not (Test-DfProcessOwner 99 10))
}

Test-Case 'unknown service never contacted' {
    Set-Item Function:script:Get-DfListenerOwners { return @(99) }
    Set-Item Function:script:Test-DfProcessOwner { return $false }
    $script:requests = 0
    Set-Item Function:script:Invoke-RestMethod { $script:requests++; throw 'Not permitted' }
    Assert-Rejected { Wait-DfConnectivity 18080 ([Diagnostics.Process]::GetCurrentProcess()) 2 } 'Port * belongs to another process*'
    Assert-True ($script:requests -eq 0)
}

Test-Case 'connectivity failure cannot report ready' {
    Set-Item Function:script:Get-DfListenerOwners { return @() }
    Assert-Rejected { Wait-DfConnectivity 18080 ([Diagnostics.Process]::GetCurrentProcess()) 1 } 'Startup connectivity was not confirmed*'
}

foreach ($nodeCase in @(
        @{ Version = '20.18.0'; Allowed = $false }, @{ Version = '20.19.0'; Allowed = $true },
        @{ Version = '21.7.0'; Allowed = $false }, @{ Version = '22.11.0'; Allowed = $false },
        @{ Version = '22.12.0'; Allowed = $true }, @{ Version = '23.0.0'; Allowed = $true })) {
    Test-Case "Node requirement: $($nodeCase.Version)" {
        $script:nodeProbe = $nodeCase.Version
        Set-Item Function:script:Get-DfVersion {
            param($Command, $Argument)
            switch -Regex ($Command) {
                'javac.exe$' { return 'javac 21.0.1' }
                'java.exe$' { return 'openjdk version "21.0.1"' }
                'node.exe$' { return "v$script:nodeProbe" }
                'git.exe$' { return 'git version 2.53.0' }
                'pnpm.cmd$' { return '12.9.1' }
            }
        }
        if ($nodeCase.Allowed) {
            Assert-True ((Get-DfDependencies $repoRoot).ContainsKey('Pnpm'))
        } else {
            Assert-Rejected { Get-DfDependencies $repoRoot } 'Node.js * is required*'
        }
    }
}

Test-Case 'JDK version mismatch rejected' {
    Set-Item Function:script:Get-DfVersion { return 'version "17.0.1"' }
    Assert-Rejected { Get-DfDependencies $repoRoot } 'JDK 21 is required*'
}

Test-Case 'pnpm version mismatch rejected' {
    Set-Item Function:script:Get-DfVersion {
        param($Command, $Argument)
        switch -Regex ($Command) {
            'javac.exe$' { return 'javac 21.0.1' }
            'java.exe$' { return 'openjdk version "21.0.1"' }
            'node.exe$' { return 'v22.12.0' }
            'git.exe$' { return 'git version 2.53.0' }
            'pnpm.cmd$' { return '10.0.0' }
        }
    }
    Assert-Rejected { Get-DfDependencies $repoRoot } 'Install the package manager*'
}

Test-Case 'native Maven failure stops before frontend installation' {
    [IO.File]::WriteAllText((Join-Path $fixture 'mvnw.cmd'), "@exit /b 7`r`n")
    Assert-Rejected { Invoke-DfBuild $fixture 'must-not-run.cmd' } 'Backend build failed*'
}

Test-Case 'native pnpm installation failure is reported' {
    [IO.File]::WriteAllText((Join-Path $fixture 'mvnw.cmd'), "@exit /b 0`r`n")
    $pnpm = Join-Path $fixture 'fake pnpm.cmd'
    [IO.File]::WriteAllText($pnpm, "@exit /b 3`r`n")
    Assert-Rejected { Invoke-DfBuild $fixture $pnpm } 'Frontend dependency installation failed*'
}

# 真实 Docker 在独立 live 验证中运行；这里固定覆盖镜像选择及故障传播。
$agentContext = Join-Path $fixture 'backend/delveforge-infrastructure/src/main/resources/agent'
$null = [IO.Directory]::CreateDirectory($agentContext)
Copy-Item -LiteralPath (Join-Path $repoRoot 'backend/delveforge-infrastructure/src/main/resources/agent/Dockerfile'),
    (Join-Path $repoRoot 'backend/delveforge-infrastructure/src/main/resources/agent/sandbox.py'),
    (Join-Path $repoRoot 'backend/delveforge-infrastructure/src/main/resources/agent/proxy.py'),
    (Join-Path $repoRoot 'backend/delveforge-infrastructure/src/main/resources/agent/offline-settings.xml') -Destination $agentContext

function Set-DockerDouble {
    $script:dockerCalls = @()
    $script:cached = $false
    $script:engine = 'linux 1.54'
    $script:dockerFailure = ''
    $script:probeVersion = 2
    $script:stale = $false
    Set-Item Function:script:Get-DfDockerExecutable { return 'docker-test' }
    Set-Item Function:script:Invoke-DfDocker {
        param($Docker, $Arguments)
        $script:dockerCalls += ,$Arguments
        $operation = $Arguments[0]
        if ($operation -eq $script:dockerFailure) { return @{ Code = 1; Output = 'failure' } }
        switch ($operation) {
            'version' { return @{ Code = 0; Output = $script:engine } }
            'image' {
                if ($Arguments[1] -eq 'ls') {
                    $value = ''
                    if ($script:cached) { $value = 'sha256:' + ('a' * 64) }
                    return @{ Code = 0; Output = $value }
                }
                return @{ Code = 0; Output = 'sha256:' + ('a' * 64) }
            }
            'build' { return @{ Code = 0; Output = '' } }
            'run' {
                $hashes = @{}
                foreach ($name in @('sandbox.py', 'proxy.py', 'offline-settings.xml')) {
                    $hashes[$name] = (Get-FileHash -LiteralPath (Join-Path $agentContext $name)).Hash.ToLowerInvariant()
                }
                if ($script:stale) { $hashes['proxy.py'] = 'obsolete' }
                $jdk = 21
                if (@($script:dockerCalls | Where-Object { $_[0] -eq 'run' }).Count -eq 2) { $jdk = 17 }
                return @{ Code = 0; Output = (@{ handshake = @{ ok = $true; tool = 'protocol';
                    result = @{ protocol = 'delveforge-sandbox'; version = $script:probeVersion } };
                    hashes = $hashes; java = 'openjdk version "' + $jdk + '.0.1"' } | ConvertTo-Json -Depth 6) }
            }
        }
        throw 'Unexpected Docker operation'
    }
}

Test-Case 'cold provisioning builds both JDKs and selects verified IDs' {
    Set-DockerDouble
    $c = @{}
    Initialize-DfAgentImages $fixture $c
    Assert-True (@($script:dockerCalls | Where-Object { $_[0] -eq 'build' }).Count -eq 2)
    Assert-True (@($script:dockerCalls | Where-Object { $_ -contains 'BASE_IMAGE=maven:3.9.9-eclipse-temurin-21' }).Count -eq 1)
    Assert-True (@($script:dockerCalls | Where-Object { $_ -contains 'BASE_IMAGE=maven:3.9.9-eclipse-temurin-17' }).Count -eq 1)
    Assert-True ($c.DELVEFORGE_AGENT_IMAGE_17 -eq ('sha256:' + ('a' * 64)))
    Assert-True ($c.DELVEFORGE_AGENT_ENABLED -eq 'true')
    foreach ($call in @($script:dockerCalls | Where-Object { $_[0] -eq 'run' })) {
        Assert-True ($call -contains ('sha256:' + ('a' * 64)))
        Assert-True ($call -contains '--network=none' -and $call -contains '--read-only')
    }
}
Test-Case 'cached images are probed without rebuild' {
    Set-DockerDouble
    $script:cached = $true
    Initialize-DfAgentImages $fixture @{}
    Assert-True (@($script:dockerCalls | Where-Object { $_[0] -eq 'build' }).Count -eq 0)
    Assert-True (@($script:dockerCalls | Where-Object { $_[0] -eq 'run' }).Count -eq 2)
}
Test-Case 'changed build inputs select a new identity' {
    $before = (Get-DfAgentInputs $fixture).Digest
    $path = Join-Path $agentContext 'Dockerfile'
    $bytes = [IO.File]::ReadAllBytes($path)
    try {
        [IO.File]::AppendAllText($path, "`n# changed input`n")
        Assert-True ((Get-DfAgentInputs $fixture).Digest -ne $before)
        Set-DockerDouble
        Initialize-DfAgentImages $fixture @{}
        $builds = @($script:dockerCalls | Where-Object { $_[0] -eq 'build' })
        Assert-True ($builds[0] -contains "delveforge-agent:protocol-v2-jdk21-$((Get-DfAgentInputs $fixture).Digest)")
    } finally { [IO.File]::WriteAllBytes($path, $bytes) }
}
Test-Case 'incompatible cached protocol refuses without overwriting tag' {
    Set-DockerDouble
    $script:cached = $true
    $script:probeVersion = 0
    Assert-Rejected { Initialize-DfAgentImages $fixture @{} } 'Agent JDK 21 image is incompatible*'
    Assert-True (@($script:dockerCalls | Where-Object { $_[0] -eq 'build' }).Count -eq 0)
}
Test-Case 'stale actual contents refuse despite compatible protocol' {
    Set-DockerDouble
    $script:cached = $true
    $script:stale = $true
    Assert-Rejected { Initialize-DfAgentImages $fixture @{} } 'Agent JDK 21 image has stale tool contents*'
}
Test-Case 'explicit image overrides still pin and probe both images' {
    Set-DockerDouble
    Initialize-DfAgentImages $fixture @{} -Image21 'advanced:21' -Image17 'advanced:17'
    Assert-True (@($script:dockerCalls | Where-Object { $_[0] -eq 'build' }).Count -eq 0)
    Assert-True (@($script:dockerCalls | Where-Object { $_ -contains 'advanced:17' }).Count -eq 1)
}
Test-Case 'missing Docker explains explicit discovery-only option' {
    Set-Item Function:script:Get-DfDockerExecutable { return $null }
    Assert-Rejected { Initialize-DfAgentImages $fixture @{} } 'Docker is missing*DisableAgent*'
    $c = @{}
    Initialize-DfAgentImages $fixture $c -DisableAgent
    Assert-True ($c.DELVEFORGE_AGENT_ENABLED -eq 'false' -and -not $c.ContainsKey('DELVEFORGE_AGENT_IMAGE_21'))
}
foreach ($engineCase in @('windows 1.54', 'linux 1.47', 'unknown')) {
    Test-Case "incompatible Engine rejects: $engineCase" {
        Set-DockerDouble
        $script:engine = $engineCase
        Assert-Rejected { Initialize-DfAgentImages $fixture @{} } 'Agent requires a Linux Docker Engine*'
    }
}
foreach ($failureCase in @('version', 'image', 'build', 'run')) {
    Test-Case "Docker failure terminates provisioning: $failureCase" {
        Set-DockerDouble
        $script:dockerFailure = $failureCase
        Assert-Rejected { Initialize-DfAgentImages $fixture @{} } '*'
    }
}
Test-Case 'bootstrap failure starts no services and restores inherited image configuration' {
    Set-StartupDoubles
    Set-Answers @('launcher-test-canary-not-a-provider-key', 'N')
    Set-Item Function:script:Initialize-DfAgentImages { Stop-DfSetup 'Docker provisioning failed.' }
    Assert-Rejected { Invoke-DfSetup $fixture } 'Docker provisioning failed*'
    Assert-True ($script:launches.Count -eq 0)
}
Test-Case 'explicit disabled-Agent selection reaches the spawned backend' {
    Set-StartupDoubles
    Set-Answers @('launcher-test-canary-not-a-provider-key', 'N')
    Set-Item Function:script:Initialize-DfAgentImages {
        param($Root, $Configuration, [switch] $DisableAgent)
        Assert-True $DisableAgent.IsPresent
        $Configuration.DELVEFORGE_AGENT_ENABLED = 'false'
    }
    Invoke-DfSetup $fixture -DisableAgent
    Assert-True ($script:launches[0].AgentEnabled -eq 'false')
    Assert-True (-not $script:launches[0].Agent21 -and -not $script:launches[0].Agent17)
}
Test-Case 'explicit advanced image arguments reach bootstrap before backend startup' {
    Set-StartupDoubles
    Set-Answers @('launcher-test-canary-not-a-provider-key', 'N')
    Set-Item Function:script:Initialize-DfAgentImages {
        param($Root, $Configuration, [string] $Image21, [string] $Image17)
        Assert-True ($Image21 -eq 'advanced:21' -and $Image17 -eq 'advanced:17')
        $Configuration.DELVEFORGE_AGENT_IMAGE_21 = 'sha256:' + ('a' * 64)
        $Configuration.DELVEFORGE_AGENT_IMAGE_17 = 'sha256:' + ('b' * 64)
    }
    Invoke-DfSetup $fixture -AgentImage21 'advanced:21' -AgentImage17 'advanced:17'
    Assert-True ($script:launches[0].Agent21 -eq ('sha256:' + ('a' * 64)))
    Assert-True ($script:launches[0].Agent17 -eq ('sha256:' + ('b' * 64)))
}

Write-Host "Launcher tests ($($PSVersionTable.PSVersion)): $passed passed, $failed failed."
Write-Host "Isolated test fixtures retained under: $fixture"
if ($failed -gt 0) {
    exit 1
}
