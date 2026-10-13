#requires -Version 5.1

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

function ConvertFrom-DfSecret {
    param([Security.SecureString] $Secret)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Read-DfKey {
    $secret = Read-Host 'DeepSeek API Key' -AsSecureString
    $plain = ConvertFrom-DfSecret $secret
    try {
        if ([string]::IsNullOrWhiteSpace($plain)) {
            $secret.Dispose()
            Stop-DfSetup 'DeepSeek API Key is required. No services were started.'
        }
    } finally {
        $plain = $null
    }
    return $secret
}

function Read-DfConfiguration {
    $configuration = @{}
    $choice = (Read-Host 'Customize configuration? [y/N]').Trim()
    if ($choice -notmatch '^(?i:y|yes)$') {
        if ($choice -and $choice -notmatch '^(?i:n|no)$') {
            Stop-DfSetup 'Enter y or N for configuration selection.'
        }
        return $configuration
    }
    Write-Host 'Leave each value blank to use application defaults. Paths may contain spaces; do not add quotes.'
    $fields = [ordered]@{
        SERVER_PORT = 'Backend port (default: 8080)'
        DELVEFORGE_AI_DEEPSEEK_MODEL = 'DeepSeek model'
        DELVEFORGE_AI_DEEPSEEK_BASEURL = 'DeepSeek base URL'
        DELVEFORGE_PERSISTENCE_DATABASE_FILE = 'SQLite database file'
        DELVEFORGE_WORKSPACE_ROOT = 'WorkingCopy root (absolute path)'
        DELVEFORGE_LOG_DIR = 'Log directory'
        DELVEFORGE_WORKSPACE_EXISTING_ROOTS = 'Historical WorkingCopy roots (comma-separated absolute paths)'
    }
    foreach ($name in $fields.Keys) {
        $value = (Read-Host $fields[$name]).Trim()
        if ($value) {
            $configuration[$name] = $value
        }
    }
    if ($configuration.ContainsKey('SERVER_PORT')) {
        $port = 0
        if (-not [int]::TryParse($configuration.SERVER_PORT, [ref] $port) -or $port -lt 1 -or $port -gt 65535) {
            Stop-DfSetup 'Backend port must be a number from 1 to 65535.'
        }
        $configuration.SERVER_PORT = $port.ToString()
    }
    if ($configuration.ContainsKey('DELVEFORGE_AI_DEEPSEEK_BASEURL')) {
        $address = $null
        if (-not [uri]::TryCreate($configuration.DELVEFORGE_AI_DEEPSEEK_BASEURL, [UriKind]::Absolute,
                [ref] $address) -or $address.Scheme -notin @('http', 'https') -or $address.UserInfo -or
                $address.Query -or $address.Fragment) {
            Stop-DfSetup 'DeepSeek base URL must be an HTTP(S) URL without credentials, query or fragment.'
        }
    }
    foreach ($name in @('DELVEFORGE_WORKSPACE_ROOT', 'DELVEFORGE_WORKSPACE_EXISTING_ROOTS')) {
        if ($configuration.ContainsKey($name)) {
            $paths = @($configuration[$name])
            if ($name -eq 'DELVEFORGE_WORKSPACE_EXISTING_ROOTS') {
                $paths = $configuration[$name].Split(',')
            }
            foreach ($path in $paths) {
                if ($path.Trim() -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+)') {
                    Stop-DfSetup 'WorkingCopy roots must be absolute paths; separate historical roots with commas.'
                }
            }
        }
    }
    return $configuration
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

function Assert-DfDatabaseAvailable {
    param([string] $Root, [hashtable] $Configuration)

    $path = './data/m4-local/delveforge.db'
    if ($Configuration.ContainsKey('DELVEFORGE_PERSISTENCE_DATABASE_FILE')) {
        $path = $Configuration.DELVEFORGE_PERSISTENCE_DATABASE_FILE
    }
    if (-not [IO.Path]::IsPathRooted($path)) {
        $path = Join-Path $Root $path
    }
    if (Test-Path -LiteralPath $path) {
        try {
            # 只检查已有文件是否被占用，不创建、复制、删除或写入数据库。
            $probe = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
            $probe.Dispose()
        } catch {
            Stop-DfSetup 'The selected database is unavailable or in use. Close its existing application first.'
        }
        Write-Host 'Existing database retained. Normal application schema migrations may run at startup.'
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
                $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/system/connectivity" `
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
    param([string] $Root)

    $ErrorActionPreference = 'Stop'
    $saved = @{}
    $secret = $null
    # 不让继承的覆盖项把“应用默认值”变成另一个数据环境；仅临时改变本进程。
    $names = @([Environment]::GetEnvironmentVariables('Process').Keys | Where-Object {
        $_ -match '^(DELVEFORGE_|DEEPSEEK_|SPRING_|SERVER_|LOGGING_)' -or
        $_ -in @('BACKEND_DEV_URL', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')
    })
    $names += 'JAVA_HOME'
    try {
        foreach ($name in ($names | Select-Object -Unique)) {
            $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            if ($name -ne 'JAVA_HOME') {
                Set-DfEnvironment $name $null
            }
        }
        Push-Location -LiteralPath $Root
        try {
            $dependencies = Get-DfDependencies $Root
            Set-DfEnvironment 'JAVA_HOME' $dependencies.JavaHome
            Write-Host 'Using m4-local: high token budgets may incur substantial API costs. Startup makes no AI requests.'
            Write-Host 'Inherited application overrides are ignored for this run; this terminal will be restored afterward.'
            $secret = Read-DfKey
            $configuration = Read-DfConfiguration
            $backendPort = 8080
            if ($configuration.ContainsKey('SERVER_PORT')) {
                $backendPort = [int] $configuration.SERVER_PORT
            }
            Assert-DfPortsFree $backendPort
            Assert-DfDatabaseAvailable $Root $configuration
            Invoke-DfBuild $Root $dependencies.Pnpm
            $pom = [xml] (Get-Content -Raw -LiteralPath (Join-Path $Root 'pom.xml'))
            $jar = Join-Path $Root "backend/delveforge-app/target/delveforge-app-$($pom.project.version).jar"
            if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
                Stop-DfSetup 'The backend build did not produce its expected JAR. No services were started.'
            }
            Assert-DfPortsFree $backendPort
            Assert-DfDatabaseAvailable $Root $configuration
            $backendCommand = @'
$Host.UI.RawUI.WindowTitle = 'DelveForge Backend'
Set-Location -LiteralPath {0}
& {1} '-jar' {2} '--spring.profiles.active=m4-local' '--spring.config.location=classpath:/application.yml'
Write-Host ('Backend exited (code ' + $LASTEXITCODE + '). Inspect the output above.')
Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
'@
            $backendCommand = $backendCommand -f (ConvertTo-DfLiteral $Root),
                (ConvertTo-DfLiteral $dependencies.Java), (ConvertTo-DfLiteral $jar)
            $configuration.DEEPSEEK_API_KEY = ConvertFrom-DfSecret $secret
            try {
                $backend = Start-DfTerminal $Root $backendCommand $configuration
            } finally {
                $configuration.Remove('DEEPSEEK_API_KEY')
                $secret.Dispose()
                $secret = $null
            }
            Wait-DfConnectivity $backendPort $backend
            $frontendCommand = @'
$Host.UI.RawUI.WindowTitle = 'DelveForge Frontend'
Set-Location -LiteralPath {0}
& {1} 'dev' '--host' '127.0.0.1' '--port' '5173' '--strictPort'
Write-Host ('Frontend exited (code ' + $LASTEXITCODE + '). Inspect the output above.')
'@
            $frontendCommand = $frontendCommand -f (ConvertTo-DfLiteral (Join-Path $Root 'frontend')),
                (ConvertTo-DfLiteral $dependencies.Pnpm)
            $frontend = Start-DfTerminal $Root $frontendCommand @{ BACKEND_DEV_URL = "http://127.0.0.1:$backendPort" }
            Wait-DfConnectivity 5173 $frontend
            Write-Host "Backend connectivity OK: http://127.0.0.1:$backendPort"
            Write-Host 'DelveForge is ready: http://127.0.0.1:5173'
            Write-Host 'Keep the two service terminals open. Stop each service with Ctrl+C, then close its terminal.'
        } finally {
            Pop-Location
        }
    } finally {
        if ($secret) {
            $secret.Dispose()
        }
        foreach ($name in $saved.Keys) {
            Set-DfEnvironment $name $saved[$name]
        }
    }
}

# 点加载仅用于确定性测试；正常用户直接执行脚本。
if ($MyInvocation.InvocationName -ne '.') {
    try {
        Invoke-DfSetup $PSScriptRoot
        exit 0
    } catch {
        if ($_.Exception.Data['DelveForgeSetup']) {
            Write-Host ('Startup failed: ' + $_.Exception.Message) -ForegroundColor Red
        } else {
            Write-Host 'Startup failed unexpectedly. Check dependency/build output and any new service terminals.' -ForegroundColor Red
        }
        Write-Host 'No existing service was stopped or user data reset. Close any terminals opened by this run before retrying.'
        exit 1
    }
}
