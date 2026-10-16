param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $SkipBuild) {
    & .\mvnw.cmd verify
    if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
}
$javaExe = Join-Path $projectRoot '.tools/jdk-17/bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    if ($env:JAVA_HOME) { $javaExe = Join-Path $env:JAVA_HOME 'bin/java.exe' }
    else { $javaExe = (Get-Command java -ErrorAction Stop).Source }
}
$testDirectory = Join-Path $projectRoot ('target/demo-verification/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testDirectory | Out-Null
$evidenceDirectory = Join-Path $projectRoot 'docs/evidence'
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
$transcript = [Text.StringBuilder]::new()
[void]$transcript.AppendLine('Actual Java process verification. Every command launches a new JVM against on-disk SQLite.')
[void]$transcript.AppendLine('Fresh isolated databases under target/demo-verification; production data is untouched.')

function New-GarageProcess([string]$DatabasePath, [string[]]$CommandArgs) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $javaExe
    $start.WorkingDirectory = $projectRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Script-owned arguments contain no quotes; quote each path/argument for the Windows process API.
    $values = @('-jar', (Join-Path $projectRoot 'target/smart-parking.jar'), '--db', $DatabasePath) + $CommandArgs
    foreach ($value in $values) { if ($value.Contains('"')) { throw 'Unexpected quote in verification argument.' } }
    $start.Arguments = ($values | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void]$process.Start()
    return $process
}

function Invoke-Garage([string]$DatabasePath, [string[]]$CommandArgs, [int]$ExpectedExit = 0) {
    $process = New-GarageProcess $DatabasePath $CommandArgs
    $process.StandardInput.Close()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(20000)) { $process.Kill(); throw 'Garage process timed out.' }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    [void]$transcript.AppendLine('> java -jar target/smart-parking.jar --db <isolated database> ' + ($CommandArgs -join ' '))
    [void]$transcript.AppendLine($stdout.TrimEnd())
    if ($stderr) { [void]$transcript.AppendLine($stderr.TrimEnd()) }
    [void]$transcript.AppendLine('Exit code: ' + $process.ExitCode)
    if ($process.ExitCode -ne $ExpectedExit) { throw "Unexpected exit code $($process.ExitCode): $stderr" }
    $process.Dispose()
    return $stdout
}

function Assert-Contains([string]$Text, [string]$Expected) {
    if (-not $Text.Contains($Expected)) { throw "Expected output missing: $Expected" }
}
function Assert-Ordered([string]$Text, [string[]]$Messages) {
    $last = -1
    foreach ($message in $Messages) {
        $position = $Text.IndexOf($message, $last + 1, [StringComparison]::Ordinal)
        if ($position -le $last) { throw "Missing or out-of-order evidence: $message" }
        $last = $position
    }
}

$db = Join-Path $testDirectory 'scenarios.db'
$normal = Invoke-Garage $db @('park','normal','CAR123','180','1','2','1000')
Assert-Ordered $normal @('A committed:','B committed:','C committed:','Final persisted state: COMPLETED')
$bFailure = Invoke-Garage $db @('demo-b','b-failure')
Assert-Ordered $bFailure @('A committed:','Stage B executing:','Starting reverse compensation.','Compensation A committed:','Final persisted state: COMPENSATED')
if ($bFailure.Contains('Compensation B committed:')) { throw 'B must not be compensated when it never committed.' }
$cFailure = Invoke-Garage $db @('demo-c','c-failure')
Assert-Ordered $cFailure @('A committed:','B committed:','Stage C executing:','Compensation B committed:','Compensation A committed:','Final persisted state: COMPENSATED')
$reopened = Invoke-Garage $db @('inspect')
foreach ($value in @('operationId=normal','status=CAPTURED','operationId=b-failure','status=CANCELLED','operationId=c-failure','status=RELEASED','Sensor space 2: BLOCKED')) { Assert-Contains $reopened $value }
$again = Invoke-Garage $db @('park','normal','CAR123','180','1','2','1000')
Assert-Contains $again 'Final persisted state: COMPLETED'
$rejected = Invoke-Garage $db @('park','oversized','BUS123','260','3','2','1000')
Assert-Contains $rejected 'Final persisted state: REJECTED'
foreach ($subsystem in @('gate','spaces','payment')) {
    [void](Invoke-Garage $db @('standalone',$subsystem,('solo-' + $subsystem)))
}
[void](Invoke-Garage $db @('cancel','normal'))
$cancelled = Invoke-Garage $db @('cancel','normal')
Assert-Ordered $cancelled @('Compensation C committed:','Compensation B committed:','Compensation A committed:')

# Genuine abrupt termination while the interactive process waits at its menu after a committed checkpoint.
$crashDb = Join-Path $testDirectory 'hard-restart.db'
$running = New-GarageProcess $crashDb @()
try {
    foreach ($line in @('9','crash-booking','CRASH123','180','1','2','1000')) { $running.StandardInput.WriteLine($line) }
    $running.StandardInput.Flush()
    $seen = [Text.StringBuilder]::new()
    for ($n = 0; $n -lt 100; $n++) {
        $lineTask = $running.StandardOutput.ReadLineAsync()
        if (-not $lineTask.Wait(15000)) { throw 'Did not observe durable checkpoint before timeout.' }
        $line = $lineTask.Result
        if ($null -eq $line) { throw 'Interactive process ended before checkpoint.' }
        [void]$seen.AppendLine($line)
        if ($line.Contains('Final persisted state: PENDING_B')) { break }
    }
    Assert-Contains $seen.ToString() 'Final persisted state: PENDING_B'
    [void]$transcript.AppendLine('> Interactive checkpoint, followed by forced process termination (no clean shutdown)')
    [void]$transcript.AppendLine($seen.ToString())
    [void](Invoke-Garage $crashDb @('inspect') 2)
    $running.Kill()
    [void]$running.WaitForExit(5000)
    [void]$transcript.AppendLine('Original Java process was forcibly killed after A committed. OS lock released.')
} finally {
    if (-not $running.HasExited) { $running.Kill() }
    $running.Dispose()
}
$pending = Invoke-Garage $crashDb @('inspect')
Assert-Contains $pending 'state=PENDING_B'
Assert-Contains $pending 'status=HELD'
$recovered = Invoke-Garage $crashDb @('recover','crash-booking')
Assert-Ordered $recovered @('A committed:','B committed:','C committed:','Final persisted state: COMPLETED')
[void](Invoke-Garage $crashDb @('inspect'))
[void]$transcript.AppendLine('PASS: success, both compensation depths, extra failure, independent subsystems, idempotency, process lock, hard termination and restart recovery.')
$evidenceFile = Join-Path $evidenceDirectory 'cli-verification.txt'
[IO.File]::WriteAllText($evidenceFile, $transcript.ToString(), [Text.UTF8Encoding]::new($false))
Write-Host "PASS. Actual CLI evidence saved to $evidenceFile"
