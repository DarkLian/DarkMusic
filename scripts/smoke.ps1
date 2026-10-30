<#
.SYNOPSIS
Runs the in-game smoke checks in sequence, each in its own fullscreen Minecraft client.

.DESCRIPTION
Each scenario is one runSmokeClient invocation of the same Gradle task and flags documented in docs/TESTING.md.
Every run gets a fresh run id; a scenario passes only when Gradle succeeds and its log carries the pass marker for
that id. The private collection is hashed before and after, and must not change. Results, logs and the hash lists
(which name private files) go to an ignored folder under .local-tools/verification/smoke.

This launches Minecraft fullscreen and plays audio. Run it only in an agreed testing window.

.EXAMPLE
./scripts/smoke.ps1 -Scenario default, default-restart
./scripts/smoke.ps1 -Scenario suite -DesktopVerify
./scripts/smoke.ps1 -Scenario reset -DryRun
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string[]]$Scenario,
    [string]$MusicDirectory = '',
    [string]$ToolsDirectory = '',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Font = '',
    [string]$Output = '',
    [double]$TimeoutMinutes = 20,
    # Runs scripts/verify.ps1 -Clean -DesktopIntegration after the scenarios.
    [switch]$DesktopVerify,
    # Checks the prerequisites and prints the commands without running them.
    [switch]$DryRun,
    # Checks this script's own pairing and pass-marker rules on synthetic files; runs nothing else.
    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot

# Gradle flags per scenario, on top of -Psmoke, the collection, the tools and -PvisualSmoke.
$scenarios = [ordered]@{
    'default' = @()
    'default-restart' = @('-PverifyHudRestart')
    'reset' = @('-PresetSmoke')
    'reset-restart' = @('-PresetSmoke', '-PverifyHudRestart')
    'advanced' = @('-PadvancedSmoke')
    'cleanup' = @('-PcleanupSmoke')
    'queue' = @('-PqueueSmoke')
    'lyrics' = @('-PlyricsSmoke')
    'preview' = @('-PpreviewSmoke')
    'native-bindings' = @('-PnativeBindingsSmoke')
}
# The full window, in order; each restart check reads what the run before it saved.
$suite = @('default', 'default-restart', 'reset', 'reset-restart', 'advanced', 'cleanup', 'queue', 'lyrics', 'preview')

function Resolve-Scenarios([string[]]$requested) {
    # powershell -File passes "a,b" as one string; split it the way an in-session call would.
    $names = @($requested | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ } |
        ForEach-Object { if ($_ -eq 'suite') { $suite } else { $_ } })
    foreach ($name in $names) {
        if (-not $scenarios.Contains($name)) { throw "Unknown scenario '$name'. Choose from: suite, $($scenarios.Keys -join ', ')." }
    }
    if (($names | Select-Object -Unique).Count -ne $names.Count) { throw 'A scenario is listed twice.' }
    for ($i = 0; $i -lt $names.Count; $i++) {
        if ($names[$i] -like '*-restart') {
            $first = $names[$i] -replace '-restart$', ''
            if ($i -eq 0 -or $names[$i - 1] -ne $first) { throw "'$($names[$i])' must directly follow '$first'." }
        }
    }
    $names
}

# A pass marker counts only for the run id this invocation's Gradle run wrote, never one left by an earlier run.
function Test-Pass([string]$log, [string]$runIdFile, [string]$previousId) {
    if (-not (Test-Path -LiteralPath $runIdFile)) { return $false }
    $id = (Get-Content -LiteralPath $runIdFile -Raw).Trim()
    if (-not $id -or $id -eq $previousId) { return $false }
    [bool](Select-String -LiteralPath $log -SimpleMatch "DARKMUSIC_SMOKE_PASS:$id" -Quiet)
}

# Gradle runs with --no-daemon, so everything one run starts descends from its wrapper: Gradle itself, a client it is
# still preparing, and a running client. Stopping that tree stops all of them, whenever the timeout falls.
function Get-Tree([int]$root) {
    $all = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, CreationDate)
    $tree = [System.Collections.Generic.List[object]]::new()
    $tree.AddRange(@($all | Where-Object ProcessId -EQ $root))
    for ($i = 0; $i -lt $tree.Count; $i++) {
        $tree.AddRange(@($all | Where-Object { $_.ParentProcessId -eq $tree[$i].ProcessId -and $_.CreationDate -ge $tree[$i].CreationDate }))
    }
    $tree
}

# True once every process of the tree has ended and nothing carrying the marker appeared during the grace period.
function Stop-Tree([int]$root, [string]$marker, [int]$graceSeconds = 10) {
    $tree = Get-Tree $root
    & taskkill.exe /PID $root /T /F 2>&1 | Out-Null
    $tree | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $quiet = (Get-Date).AddSeconds($graceSeconds)
    $limit = $quiet.AddSeconds(30)
    while ((Get-Date) -lt $limit) {
        $current = @(Get-CimInstance Win32_Process)
        # Same id and start time: a reused process id is not the old process.
        $alive = @($tree | Where-Object { $old = $_; $current | Where-Object { $_.ProcessId -eq $old.ProcessId -and $_.CreationDate -eq $old.CreationDate } })
        $late = @($current | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($marker) })
        $late + $alive | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        if (-not $alive -and -not $late -and (Get-Date) -ge $quiet) { return $true }
        Start-Sleep -Milliseconds 500
    }
    $false
}

if ($SelfTest) {
    $failures = @()
    function Expect([string]$name, [scriptblock]$check) {
        try { if (-not (& $check)) { $script:failures += $name } } catch { $script:failures += "$name ($_)" }
    }
    function Rejects([string[]]$names) { try { Resolve-Scenarios $names | Out-Null; $false } catch { $true } }
    Expect 'suite expands in order' { (Resolve-Scenarios @('suite')) -join ',' -eq ($suite -join ',') }
    Expect 'restart alone is rejected' { Rejects @('default-restart') }
    Expect 'restart after another scenario is rejected' { Rejects @('reset', 'default-restart') }
    Expect 'duplicates are rejected' { Rejects @('queue', 'queue') }
    Expect 'unknown names are rejected' { Rejects @('everything') }
    Expect 'a comma-joined list is split' { (Resolve-Scenarios @('reset,reset-restart')) -join ',' -eq 'reset,reset-restart' }
    $temp = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $temp | Out-Null
    try {
        $log = Join-Path $temp 'step.log'
        $runId = Join-Path $temp 'smoke-run-id.txt'
        Set-Content -LiteralPath $runId 'new-run'
        Set-Content -LiteralPath $log 'DARKMUSIC_SMOKE_PASS:old-run'
        Expect 'a marker from an earlier run does not pass' { -not (Test-Pass $log $runId 'old-run') }
        Set-Content -LiteralPath $runId 'old-run'
        Expect 'an unchanged run id does not pass' { -not (Test-Pass $log $runId 'old-run') }
        Set-Content -LiteralPath $runId 'new-run'
        Add-Content -LiteralPath $log 'DARKMUSIC_SMOKE_PASS:new-run checks'
        Expect 'this run''s marker passes' { Test-Pass $log $runId 'old-run' }
        Expect 'a missing run id does not pass' { -not (Test-Pass $log (Join-Path $temp 'missing') 'old-run') }
    } finally { Remove-Item -LiteralPath $temp -Recurse -Force }
    # A wrapper whose child is still starting up, like Gradle before it launches the client.
    $marker = "darkmusic.selfTest=$([guid]::NewGuid())"
    $parent = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command',
        "Start-Process powershell -WindowStyle Hidden -ArgumentList '-NoProfile','-Command','Start-Sleep 300 # $marker'; Start-Sleep 300"
    $deadline = (Get-Date).AddSeconds(20)
    $child = { Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $parent.Id -and $_.Name -eq 'powershell.exe' } }
    while (-not (& $child) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 200
    }
    $tree = Get-Tree $parent.Id
    Expect 'the child started' { [bool](& $child) }
    Expect 'the tree includes the child' { $tree.ProcessId -contains (& $child).ProcessId }
    Expect 'stopping the tree ends every process in it' { Stop-Tree $parent.Id $marker 2 }
    Expect 'nothing of the tree is left' {
        -not (Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine.Contains($marker) })
    }
    if ($failures) { throw "Self-test failed: $($failures -join '; ')" }
    'Self-test passed.'
    return
}

$names = Resolve-Scenarios $Scenario

if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME to a JDK 25, or pass -JavaHome.'
}
if (-not $MusicDirectory) { $MusicDirectory = Join-Path $projectDirectory 'run/config/darkmusic/music' }
if (-not (Test-Path -LiteralPath $MusicDirectory -PathType Container)) { throw "The private collection is missing: $MusicDirectory" }
$MusicDirectory = (Resolve-Path -LiteralPath $MusicDirectory).Path
$musicFiles = @(Get-ChildItem -LiteralPath $MusicDirectory -Recurse -File | Sort-Object FullName)
if (-not $musicFiles.Count) { throw "The private collection is empty: $MusicDirectory" }
# The private collection is Opus, so every check that plays music needs the managed FFmpeg.
if (-not $ToolsDirectory) {
    $managed = Join-Path $projectDirectory 'run/config/darkmusic/tools/managed'
    $bundle = Get-ChildItem -LiteralPath $managed -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'ffmpeg.exe') } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $bundle) { throw 'No managed tool bundle found. Install the tools in game once, or pass -ToolsDirectory.' }
    $ToolsDirectory = $bundle.FullName
}
$ToolsDirectory = (Resolve-Path -LiteralPath $ToolsDirectory).Path
foreach ($tool in @('ffmpeg.exe', 'ffprobe.exe', 'yt-dlp.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $ToolsDirectory $tool) -PathType Leaf)) { throw "Missing tool: $tool in $ToolsDirectory" }
}
if (-not $Font) { $Font = Join-Path $projectDirectory 'run/resourcepacks/SmoothFont_1.21.zip' }
if (-not (Test-Path -LiteralPath $Font -PathType Leaf)) { throw "Smoke checks and captures need SmoothFont: $Font" }
$Font = (Resolve-Path -LiteralPath $Font).Path

$common = @('runSmokeClient', '--no-daemon', '-Psmoke', "-PmusicDirectory=$MusicDirectory", "-PdownloadToolsDirectory=$ToolsDirectory",
    "-PsmokeFont=$Font", '-PvisualSmoke')
if ($DryRun) {
    foreach ($name in $names) { "${name}: gradlew $(($common + $scenarios[$name] + '-PsmokeOwner=<run guid>') -join ' ')" }
    if ($DesktopVerify) { "verify: scripts/verify.ps1 -Clean -DesktopIntegration -MusicDirectory $MusicDirectory -ToolsDirectory $ToolsDirectory" }
    return
}

if (-not $Output) {
    $Output = Join-Path $projectDirectory ".local-tools/verification/smoke/$(Get-Date -Format 'yyyyMMdd-HHmmss')"
}
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$env:JAVA_HOME = $JavaHome
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
$gradle = Join-Path $projectDirectory 'gradlew.bat'
$runIdFile = Join-Path $projectDirectory 'build/smoke-run-id.txt'

function Get-Hashes {
    $musicFiles | ForEach-Object { "$($_.FullName)|$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)" }
}
$before = @(Get-Hashes)
$before | Set-Content -Encoding UTF8 (Join-Path $Output 'collection-before.txt')

$results = [System.Collections.Generic.List[object]]::new()
function Save-Results {
    $results | ForEach-Object { "{0,-16} {1,-8} {2}" -f $_.Name, $_.Status, $_.Detail } |
        Set-Content -Encoding UTF8 (Join-Path $Output 'summary.txt')
}

try {
    foreach ($name in $names) {
        $first = $name -replace '-restart$', ''
        if ($name -ne $first -and ($results | Where-Object Name -EQ $first).Status -ne 'passed') {
            $results.Add([pscustomobject]@{ Name = $name; Status = 'skipped'; Detail = "'$first' did not pass" })
            Save-Results
            continue
        }
        $log = Join-Path $Output "$name.log"
        $owner = [guid]::NewGuid().ToString()
        $previousId = if (Test-Path -LiteralPath $runIdFile) { (Get-Content -LiteralPath $runIdFile -Raw).Trim() } else { '' }
        $started = Get-Date
        # Start-Process joins arguments with spaces, so quote any that contain one.
        $arguments = ($common + $scenarios[$name] + "-PsmokeOwner=$owner") | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }
        $process = Start-Process -FilePath $gradle -ArgumentList $arguments `
            -WorkingDirectory $projectDirectory -NoNewWindow -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        $null = $process.Handle  # keeps ExitCode readable after the process ends
        $timedOut = -not $process.WaitForExit([int]($TimeoutMinutes * 60 * 1000))
        $stopped = $true
        if (-not $timedOut) {
            $exit = $process.ExitCode
        } else {
            $stopped = Stop-Tree $process.Id "darkmusic.smokeOwner=$owner"
            $exit = 'timeout'
        }
        Get-Content -LiteralPath "$log.err" -ErrorAction SilentlyContinue | Add-Content -LiteralPath $log
        Remove-Item -LiteralPath "$log.err" -ErrorAction SilentlyContinue
        $minutes = [math]::Round(((Get-Date) - $started).TotalMinutes, 1)
        $status = if (-not $stopped) { 'stuck' } elseif ($timedOut) { 'timeout' } elseif ($exit -eq 0 -and (Test-Pass $log $runIdFile $previousId)) { 'passed' } else { 'failed' }
        $results.Add([pscustomobject]@{ Name = $name; Status = $status; Detail = "exit=$exit, $minutes min, $log" })
        Save-Results
        # Starting another client beside one that may still be running would interfere with both.
        if (-not $stopped) { throw "Could not confirm that '$name' stopped; the remaining scenarios were not run." }
    }
    if ($DesktopVerify) {
        $log = Join-Path $Output 'verify-clean-desktop.log'
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify.ps1') -Clean -DesktopIntegration `
                -MusicDirectory $MusicDirectory -ToolsDirectory $ToolsDirectory *> $log
            $exit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previous }
        $results.Add([pscustomobject]@{ Name = 'verify'; Status = $(if ($exit -eq 0) { 'passed' } else { 'failed' }); Detail = "exit=$exit, $log" })
        Save-Results
    }
} finally {
    $after = @(Get-Hashes)
    $after | Set-Content -Encoding UTF8 (Join-Path $Output 'collection-after.txt')
    $unchanged = $null -eq (Compare-Object $before $after)
    $results.Add([pscustomobject]@{ Name = 'collection'; Status = $(if ($unchanged) { 'passed' } else { 'failed' }); Detail = "$($musicFiles.Count) files, unchanged=$unchanged" })
    Save-Results
    Get-Content -LiteralPath (Join-Path $Output 'summary.txt')
}
if ($results | Where-Object Status -NE 'passed') { exit 1 }
