#requires -Version 5.1

param(
    [string] $DataDirectory = (Join-Path $PSScriptRoot 'data/workbench'),
    [ValidateRange(1,65535)][int] $BackendPort = 8080,
    [ValidateSet('Ensure','Restart','Rebuild')][string] $EnvironmentAction = 'Ensure'
)

function Stop-DfSetup {
    param([string] $Message)

    $failure = New-Object System.InvalidOperationException($Message)
    $failure.Data['DelveForgeSetup'] = $true
    throw $failure
}

function Get-DfCommand {
    param([string] $Name)

    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        Stop-DfSetup "Missing dependency: $Name. Install it and open a new PowerShell terminal."
    }
    return $command.Source
}

function Get-DfVersion {
    param([string] $Command, [string] $Argument)

    # Java 的正常版本信息写到 stderr；5.1 不应把它当成构建失败。
    $ErrorActionPreference = 'Continue'
    $output = & $Command $Argument 2>&1
    if ($LASTEXITCODE -ne 0) {
        Stop-DfSetup 'A dependency version check failed. Check JDK, Git, Node.js and pnpm installation.'
    }
    return ($output | Out-String).Trim()
}

function Get-DfDependencies {
    param([string] $Root)

    foreach ($file in @('pom.xml', 'mvnw.cmd', '.mvn/wrapper/maven-wrapper.properties',
            'frontend/package.json', 'frontend/pnpm-lock.yaml')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $file) -PathType Leaf)) {
            Stop-DfSetup 'Required project files are missing. Run the script from a complete DelveForge checkout.'
        }
    }
    if ($env:JAVA_HOME) {
        $javaHome = $env:JAVA_HOME
    } else {
        $javaHome = Split-Path (Split-Path (Get-DfCommand 'java.exe') -Parent) -Parent
    }
    $java = Join-Path $javaHome 'bin/java.exe'
    $javac = Join-Path $javaHome 'bin/javac.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javac -PathType Leaf)) {
        Stop-DfSetup 'JDK 21 is required. Set JAVA_HOME to a JDK 21 directory containing bin/java.exe and bin/javac.exe.'
    }
    $javaVersion = Get-DfVersion $java '-version'
    $javacVersion = Get-DfVersion $javac '-version'
    if ($javaVersion -notmatch 'version "21\.' -or $javacVersion -notmatch '^javac 21\.') {
        Stop-DfSetup 'JDK 21 is required. Check JAVA_HOME and the installed Java version.'
    }
    $node = Get-DfCommand 'node.exe'
    $nodeText = Get-DfVersion $node '--version'
    if ($nodeText -notmatch '^v(\d+\.\d+\.\d+)$') {
        Stop-DfSetup 'Cannot determine the Node.js version.'
    }
    $nodeVersion = [version] $Matches[1]
    if (-not (($nodeVersion -ge [version]'20.19.0' -and $nodeVersion.Major -eq 20) -or
            $nodeVersion -ge [version]'22.12.0')) {
        Stop-DfSetup 'Node.js ^20.19.0 or >=22.12.0 is required by the frontend.'
    }
    $gitVersion = Get-DfVersion (Get-DfCommand 'git.exe') '--version'
    if ($gitVersion -notmatch '^git version \d+\.') {
        Stop-DfSetup 'Cannot determine the Git CLI version.'
    }
    $package = Get-Content -Raw -LiteralPath (Join-Path $Root 'frontend/package.json') | ConvertFrom-Json
    $pnpm = Get-DfCommand 'pnpm.cmd'
    $pnpmVersion = Get-DfVersion $pnpm '--version'
    if ($package.packageManager -ne "pnpm@$pnpmVersion") {
        Stop-DfSetup "Install the package manager specified in frontend/package.json ($($package.packageManager))."
    }
    foreach ($command in @('Get-NetTCPConnection', 'Get-CimInstance')) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
            Stop-DfSetup 'Windows networking/process inspection commands are required for safe startup.'
        }
    }
    Write-Host "Dependencies OK: JDK 21, Node $nodeVersion, $gitVersion, pnpm $pnpmVersion."
    return @{ Java = $java; JavaHome = $javaHome; Pnpm = $pnpm }
}

function Assert-DfPortsFree {
    param([int] $BackendPort, [int] $FrontendPort = 5173)

    if ($BackendPort -eq $FrontendPort) {
        Stop-DfSetup 'Backend and frontend ports must be different; Vite uses 5173.'
    }
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    foreach ($port in @($BackendPort, $FrontendPort)) {
        if (@($listeners | Where-Object { $_.Port -eq $port }).Count -gt 0) {
            Stop-DfSetup "Port $port is occupied. Free this port before retrying; backend ports can also be customized. No process was stopped."
        }
    }
}

function Invoke-DfBuild {
    param([string] $Root, [string] $Pnpm)

    Write-Host 'Building the latest backend (tests skipped)...'
    & (Join-Path $Root 'mvnw.cmd') '-DskipTests' 'package' | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Stop-DfSetup 'Backend build failed. Check the build output; no services were started.'
    }
    Push-Location -LiteralPath (Join-Path $Root 'frontend')
    try {
        Write-Host 'Installing frontend dependencies from the lockfile...'
        & $Pnpm 'install' '--frozen-lockfile' | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Stop-DfSetup 'Frontend dependency installation failed. Check pnpm output; no services were started.'
        }
    } finally {
        Pop-Location
    }
}

function ConvertTo-DfLiteral {
    param([string] $Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Set-DfEnvironment {
    param([string] $Name, $Value)

    # PowerShell 向 .NET string 参数传 $null 时可能变成空字符串；显式删除才能恢复默认值。
    if ($null -eq $Value) {
        $Value = [System.Management.Automation.Language.NullString]::Value
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Start-DfTerminal {
    param([string] $Root, [string] $Command, [hashtable] $Environment)

    $shellName = 'powershell.exe'
    if ($PSVersionTable.PSEdition -eq 'Core') {
        $shellName = 'pwsh.exe'
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($Command))
    $previous = @{}
    try {
        # 密钥仅经进程环境继承；编码命令中只包含启动代码和非敏感路径。
        foreach ($name in $Environment.Keys) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            Set-DfEnvironment $name $Environment[$name]
        }
        return Start-Process -FilePath (Join-Path $PSHOME $shellName) -WorkingDirectory $Root `
            -ArgumentList @('-NoProfile', '-NoExit', '-EncodedCommand', $encoded) -WindowStyle Normal -PassThru
    } finally {
        foreach ($name in $previous.Keys) {
            Set-DfEnvironment $name $previous[$name]
        }
    }
}

function Get-DfListenerOwners {
    param([int] $Port)
    # 不依赖命令行内容判定身份，也不读取可能带凭据的进程参数。
    return @(Get-NetTCPConnection -State Listen -ErrorAction Stop |
        Where-Object { $_.LocalPort -eq $Port } | Select-Object -ExpandProperty OwningProcess -Unique)
}

function Test-DfProcessOwner {
    param([int] $Owner, [int] $TerminalId)

    $processes = @(Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId -ErrorAction Stop)
    $parents = @{}
    foreach ($process in $processes) {
        $parents[[int] $process.ProcessId] = [int] $process.ParentProcessId
    }
    $visited = @{}
    while ($Owner -and -not $visited.ContainsKey($Owner)) {
        if ($Owner -eq $TerminalId) {
            return $true
        }
        $visited[$Owner] = $true
        if (-not $parents.ContainsKey($Owner)) {
            return $false
        }
        $Owner = $parents[$Owner]
    }
    return $false
}

function Wait-DfConnectivity {
    param([int] $Port, [System.Diagnostics.Process] $Terminal, [int] $TimeoutSeconds = 120)

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($Terminal.HasExited) {
            Stop-DfSetup 'A startup terminal exited before connectivity was confirmed.'
        }
        $owners = @(Get-DfListenerOwners $Port)
        foreach ($owner in $owners) {
            if (-not (Test-DfProcessOwner $owner $Terminal.Id)) {
                Stop-DfSetup "Port $Port belongs to another process. It was not contacted or stopped."
            }
        }
        if ($owners.Count -gt 0) {
            try {
                $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/workbench/health" `
                    -TimeoutSec 3 -ErrorAction Stop
                if ($response.service -eq 'delveforge' -and $response.status -eq 'UP') {
                    return
                }
            } catch {
                # 启动期间只重查只读 Connectivity，不重放业务请求。
            }
        }
        Start-Sleep -Milliseconds 1000
    }
    Stop-DfSetup "Startup connectivity was not confirmed on port $Port. Inspect the new terminals and close them before trying again."
}

function Invoke-DfSetup {
    param([string] $Root, [string] $DataDirectory, [int] $BackendPort, [string] $EnvironmentAction)
    $ErrorActionPreference = 'Stop'
    $dataPath = [IO.Path]::GetFullPath($DataDirectory)
    $providerEnvironment = @{}
    foreach ($name in @('OPENAI_API_KEY','ANTHROPIC_API_KEY','GEMINI_API_KEY','GOOGLE_API_KEY',
            'DEEPSEEK_API_KEY','ZAI_API_KEY','DASHSCOPE_API_KEY','MOONSHOT_API_KEY','OPENROUTER_API_KEY',
            'DELVEFORGE_CREDENTIAL_MASTER_KEY')) {
        $providerEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $previousJava = [Environment]::GetEnvironmentVariable('JAVA_HOME','Process')
    Push-Location -LiteralPath $Root
    try {
        $dependencies = Get-DfDependencies $Root
        Assert-DfPortsFree $BackendPort
        foreach ($name in $providerEnvironment.Keys) { Set-DfEnvironment $name $null }
        Set-DfEnvironment 'JAVA_HOME' $dependencies.JavaHome
        $initializer = Join-Path $Root 'runtime/workbench/Initialize-Environment.ps1'
        & $initializer -DataDirectory $dataPath -Action $EnvironmentAction
        Invoke-DfBuild $Root $dependencies.Pnpm
        Assert-DfPortsFree $BackendPort
        $pom = [xml](Get-Content -Raw -LiteralPath (Join-Path $Root 'pom.xml'))
        $jar = Join-Path $Root "backend/delveforge-app/target/delveforge-app-$($pom.project.version).jar"
        $backendCommand = 'Set-Location -LiteralPath {0}; & {1} -jar {2}' -f (ConvertTo-DfLiteral $Root),
            (ConvertTo-DfLiteral $dependencies.Java), (ConvertTo-DfLiteral $jar)
        $configuration = @{ SERVER_PORT = $BackendPort.ToString(); DELVEFORGE_WORKBENCH_DIRECTORY = $dataPath }
        foreach ($name in $providerEnvironment.Keys) {
            if ($providerEnvironment[$name]) { $configuration[$name] = $providerEnvironment[$name] }
        }
        $backend = Start-DfTerminal $Root $backendCommand $configuration
        Wait-DfConnectivity $BackendPort $backend
        $frontendCommand = "Set-Location -LiteralPath {0}; & {1} dev --host 127.0.0.1 --port 5173 --strictPort" -f (
            ConvertTo-DfLiteral (Join-Path $Root 'frontend')), (ConvertTo-DfLiteral $dependencies.Pnpm)
        $frontend = Start-DfTerminal $Root $frontendCommand @{ BACKEND_DEV_URL = "http://127.0.0.1:$BackendPort" }
        Wait-DfConnectivity 5173 $frontend
        Write-Host 'DelveForge: http://127.0.0.1:5173'
        Write-Host "Windows workcopies: $(Join-Path $dataPath 'projects')"
    } finally {
        foreach ($name in $providerEnvironment.Keys) { Set-DfEnvironment $name $providerEnvironment[$name] }
        Set-DfEnvironment 'JAVA_HOME' $previousJava
        Pop-Location
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    Invoke-DfSetup -Root $PSScriptRoot -DataDirectory $DataDirectory -BackendPort $BackendPort -EnvironmentAction $EnvironmentAction
}
