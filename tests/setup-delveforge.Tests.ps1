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
        'Test-DfProcessOwner', 'Invoke-RestMethod', 'Get-DfCommand', 'Get-DfVersion')) {
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
        'DELVEFORGE_PERSISTENCE_DATABASE_FILE', 'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS')
    $saved = @{}
    foreach ($name in $names) {
        $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, 'inherited-test-canary', 'Process')
    }
    $db = Join-Path $fixture 'data/m4-local/delveforge.db'
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

Write-Host "Launcher tests ($($PSVersionTable.PSVersion)): $passed passed, $failed failed."
Write-Host "Isolated test fixtures retained under: $fixture"
if ($failed -gt 0) {
    exit 1
}
