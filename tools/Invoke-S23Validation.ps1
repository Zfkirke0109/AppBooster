#requires -Version 5.1

[CmdletBinding()]
param(
    [ValidateSet(
        "VerifyArtifact",
        "NewSession",
        "StartCapture",
        "Install",
        "Launch",
        "Snapshot",
        "StopCapture",
        "Status"
    )]
    [string]$Action = "VerifyArtifact",

    [string]$Serial,

    [string]$ApkPath,

    [string]$ExpectedSignerSha256 = "BB93CAB28F64A1CD14C92F771A91D4E000498448723CFF3C6571A48CC6715723"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:PackageName = "com.zfkirke0109.galaxyoptidroid"
$script:ExpectedModel = "SM-S918U1"
$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:ValidationRoot = Join-Path $script:RepoRoot "validation"
$script:LatestSessionFile = Join-Path $script:ValidationRoot "latest-session.txt"
$script:AdbPath = (Get-Command adb.exe -ErrorAction Stop).Source
$script:DeviceSerial = $null
$script:ExpectedSignerSha256 = $ExpectedSignerSha256

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $script:RepoRoot "app\build\outputs\apk\release\app-release.apk"
}

function Invoke-NativeCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    $lines = @(& $FilePath @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $output = $lines -join [Environment]::NewLine

    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "Command failed with exit code ${exitCode}: $FilePath $($Arguments -join ' ')`n$output"
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Invoke-AdbCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,

        [switch]$AllowFailure
    )

    if ([string]::IsNullOrWhiteSpace($script:DeviceSerial)) {
        throw "A device serial has not been resolved."
    }

    $adbArguments = @("-s", $script:DeviceSerial) + $Arguments
    Invoke-NativeCommand -FilePath $script:AdbPath -Arguments $adbArguments -AllowFailure:$AllowFailure
}

function Resolve-DeviceSerial {
    param([string]$RequestedSerial)

    $devicesResult = Invoke-NativeCommand -FilePath $script:AdbPath -Arguments @("devices", "-l")
    $connected = @(
        $devicesResult.Output -split "`r?`n" |
            ForEach-Object {
                if ($_ -match "^(\S+)\s+device(?:\s|$)") {
                    $Matches[1]
                }
            }
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedSerial)) {
        if ($connected -notcontains $RequestedSerial) {
            throw "Requested device '$RequestedSerial' is not connected. Connected devices: $($connected -join ', ')"
        }
        return $RequestedSerial
    }

    if ($connected.Count -eq 0) {
        throw "No authorized ADB device is connected."
    }
    if ($connected.Count -gt 1) {
        throw "Multiple ADB devices are connected. Pass -Serial explicitly: $($connected -join ', ')"
    }

    return $connected[0]
}

function Get-BuildToolInfo {
    $sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } else {
        Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }

    $buildToolsRoot = Join-Path $sdkRoot "build-tools"
    $latest = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1

    if ($null -eq $latest) {
        throw "No Android build-tools installation was found under $buildToolsRoot."
    }

    $aapt = Join-Path $latest.FullName "aapt.exe"
    $apksigner = Join-Path $latest.FullName "apksigner.bat"
    if (-not (Test-Path -LiteralPath $aapt) -or -not (Test-Path -LiteralPath $apksigner)) {
        throw "aapt or apksigner is missing from $($latest.FullName)."
    }

    [pscustomobject]@{
        Version = $latest.Name
        Aapt = $aapt
        ApkSigner = $apksigner
    }
}

function Get-ApkIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [string]$RequiredSigner
    )

    $resolvedPath = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    $tools = Get-BuildToolInfo
    $badging = Invoke-NativeCommand -FilePath $tools.Aapt -Arguments @("dump", "badging", $resolvedPath)
    $packageLine = ($badging.Output -split "`r?`n" | Select-Object -First 1)
    if ($packageLine -notmatch "name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'") {
        throw "Unable to parse APK package identity from aapt output."
    }

    $packageName = $Matches[1]
    $versionCode = $Matches[2]
    $versionName = $Matches[3]

    $signature = Invoke-NativeCommand -FilePath $tools.ApkSigner -Arguments @(
        "verify",
        "--verbose",
        "--print-certs",
        $resolvedPath
    )
    if ($signature.Output -notmatch "(?im)^Signer #1 certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$") {
        throw "Unable to parse APK signer SHA-256 from apksigner output."
    }
    $signer = ($Matches[1] -replace ":", "").ToUpperInvariant()
    $required = if ([string]::IsNullOrWhiteSpace($RequiredSigner)) {
        $null
    } else {
        ($RequiredSigner -replace "[:\s]", "").ToUpperInvariant()
    }

    [pscustomobject]@{
        Path = $resolvedPath
        FileSha256 = (Get-FileHash -LiteralPath $resolvedPath -Algorithm SHA256).Hash.ToUpperInvariant()
        PackageName = $packageName
        VersionName = $versionName
        VersionCode = $versionCode
        SignerSha256 = $signer
        SignerMatches = ($null -eq $required -or $signer -eq $required)
        BuildToolsVersion = $tools.Version
    }
}

function Get-ExpectedBuildIdentity {
    $buildFile = Join-Path $script:RepoRoot "app\build.gradle.kts"
    $contents = Get-Content -LiteralPath $buildFile -Raw
    $values = @{}
    foreach ($name in @("major", "minor", "patch")) {
        if ($contents -notmatch "val\s+$name\s*=\s*(\d+)") {
            throw "Unable to read '$name' from $buildFile."
        }
        $values[$name] = [int]$Matches[1]
    }

    [pscustomobject]@{
        VersionName = "$($values.major).$($values.minor).$($values.patch)"
        VersionCode = ($values.major * 10000 + $values.minor * 100 + $values.patch).ToString()
    }
}

function Assert-ReleaseArtifact {
    $identity = Get-ApkIdentity -Path $ApkPath -RequiredSigner $script:ExpectedSignerSha256
    $expectedBuild = Get-ExpectedBuildIdentity
    if ($identity.PackageName -ne $script:PackageName) {
        throw "Unexpected APK package '$($identity.PackageName)'; expected '$($script:PackageName)'."
    }
    if ($identity.VersionName -ne $expectedBuild.VersionName -or $identity.VersionCode -ne $expectedBuild.VersionCode) {
        throw "APK version $($identity.VersionName)/$($identity.VersionCode) does not match source $($expectedBuild.VersionName)/$($expectedBuild.VersionCode)."
    }
    if (-not $identity.SignerMatches) {
        throw "APK signer mismatch. Expected $($script:ExpectedSignerSha256), got $($identity.SignerSha256)."
    }

    $identity
}

function Initialize-ValidationSessionDirectory {
    New-Item -ItemType Directory -Force $script:ValidationRoot | Out-Null
    $name = "s23_$(Get-Date -Format 'yyyyMMdd_HHmmss')"
    $directory = Join-Path $script:ValidationRoot $name
    New-Item -ItemType Directory -Path $directory | Out-Null
    Set-Content -LiteralPath $script:LatestSessionFile -Value $name -Encoding utf8
    return $directory
}

function Get-ValidationSessionDirectory {
    if (-not (Test-Path -LiteralPath $script:LatestSessionFile)) {
        throw "No validation session exists. Run -Action NewSession first."
    }

    $name = (Get-Content -LiteralPath $script:LatestSessionFile -Raw).Trim()
    if ($name -notmatch "^s23_\d{8}_\d{6}$") {
        throw "Invalid latest validation session name '$name'."
    }

    $directory = Join-Path $script:ValidationRoot $name
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw "Validation session directory is missing: $directory"
    }
    return $directory
}

function Write-AdbEvidence {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SessionDirectory,

        [Parameter(Mandatory = $true)]
        [string]$FileName,

        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $result = Invoke-AdbCommand -Arguments $Arguments -AllowFailure
    $header = "exitCode=$($result.ExitCode)`r`ncommand=adb -s $($script:DeviceSerial) $($Arguments -join ' ')`r`n`r`n"
    Set-Content -LiteralPath (Join-Path $SessionDirectory $FileName) -Value ($header + $result.Output) -Encoding utf8
    return $result
}

function Get-InstalledApkIdentity {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SessionDirectory,

        [string]$Suffix = "before"
    )

    $pathResult = Invoke-AdbCommand -Arguments @("shell", "pm", "path", $script:PackageName) -AllowFailure
    if ($pathResult.ExitCode -ne 0 -or $pathResult.Output -notmatch "(?m)^package:(.+base\.apk)\s*$") {
        return $null
    }

    $remotePath = $Matches[1].Trim()
    $localPath = Join-Path $SessionDirectory "installed-$Suffix.apk"
    Invoke-AdbCommand -Arguments @("pull", $remotePath, $localPath) | Out-Null
    return Get-ApkIdentity -Path $localPath -RequiredSigner $script:ExpectedSignerSha256
}

function Save-DeviceSnapshot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SessionDirectory,

        [Parameter(Mandatory = $true)]
        [string]$Label
    )

    $snapshot = Join-Path $SessionDirectory ("snapshot-{0}-{1}" -f $Label, (Get-Date -Format "yyyyMMdd_HHmmss"))
    New-Item -ItemType Directory -Path $snapshot | Out-Null

    Write-AdbEvidence -SessionDirectory $snapshot -FileName "package.txt" -Arguments @("shell", "dumpsys", "package", $script:PackageName) | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "services.txt" -Arguments @("shell", "dumpsys", "activity", "services", $script:PackageName) | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "exit-info.txt" -Arguments @("shell", "dumpsys", "activity", "exit-info", $script:PackageName) | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "jobscheduler.txt" -Arguments @("shell", "dumpsys", "jobscheduler") | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "meminfo.txt" -Arguments @("shell", "dumpsys", "meminfo", $script:PackageName) | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "gfxinfo.txt" -Arguments @("shell", "dumpsys", "gfxinfo", $script:PackageName, "framestats") | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "battery.txt" -Arguments @("shell", "dumpsys", "battery") | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "thermal.txt" -Arguments @("shell", "dumpsys", "thermalservice") | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "standby-bucket.txt" -Arguments @("shell", "am", "get-standby-bucket", $script:PackageName) | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "package-help.txt" -Arguments @("shell", "cmd", "package", "help") | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "package-dump.txt" -Arguments @("shell", "cmd", "package", "dump", $script:PackageName) | Out-Null
    Write-AdbEvidence -SessionDirectory $snapshot -FileName "telemetry-files.txt" -Arguments @("shell", "ls -la '/sdcard/Download/Galaxy OptiDroid/Telemetry'") | Out-Null

    $remoteUi = "/sdcard/galaxy-optidroid-window.xml"
    $uiDump = Invoke-AdbCommand -Arguments @("shell", "uiautomator", "dump", $remoteUi) -AllowFailure
    if ($uiDump.ExitCode -eq 0) {
        Invoke-AdbCommand -Arguments @("pull", $remoteUi, (Join-Path $snapshot "window.xml")) -AllowFailure | Out-Null
        Invoke-AdbCommand -Arguments @("shell", "rm", "-f", $remoteUi) -AllowFailure | Out-Null
    }

    $remoteScreenshot = "/sdcard/galaxy-optidroid-validation.png"
    $screenshot = Invoke-AdbCommand -Arguments @("shell", "screencap", "-p", $remoteScreenshot) -AllowFailure
    if ($screenshot.ExitCode -eq 0) {
        Invoke-AdbCommand -Arguments @("pull", $remoteScreenshot, (Join-Path $snapshot "screen.png")) -AllowFailure | Out-Null
        Invoke-AdbCommand -Arguments @("shell", "rm", "-f", $remoteScreenshot) -AllowFailure | Out-Null
    }

    "Snapshot saved: $snapshot"
}

function Get-SessionData {
    param([string]$SessionDirectory)

    $sessionFile = Join-Path $SessionDirectory "session.json"
    if (-not (Test-Path -LiteralPath $sessionFile)) {
        throw "Session metadata is missing: $sessionFile"
    }
    Get-Content -LiteralPath $sessionFile -Raw | ConvertFrom-Json
}

function Assert-CaptureRunning {
    param([string]$SessionDirectory)

    $pidFile = Join-Path $SessionDirectory "logcat.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) {
        throw "Logcat capture is not armed. Run -Action StartCapture first."
    }

    $capturePid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
    $process = Get-Process -Id $capturePid -ErrorAction SilentlyContinue
    if ($null -eq $process -or $process.ProcessName -ne "adb") {
        throw "Recorded logcat process $capturePid is not running."
    }
    return $capturePid
}

switch ($Action) {
    "VerifyArtifact" {
        $identity = Assert-ReleaseArtifact
        $identity | Format-List
    }

    "NewSession" {
        $identity = Assert-ReleaseArtifact
        $script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $Serial
        $sessionDirectory = Initialize-ValidationSessionDirectory

        $model = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.product.model")).Output.Trim()
        $android = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.build.version.release")).Output.Trim()
        $sdk = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.build.version.sdk")).Output.Trim()
        $build = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.build.display.id")).Output.Trim()
        $patch = (Invoke-AdbCommand -Arguments @("shell", "getprop", "ro.build.version.security_patch")).Output.Trim()
        $installedIdentity = Get-InstalledApkIdentity -SessionDirectory $sessionDirectory
        $installAllowed = $null -eq $installedIdentity -or $installedIdentity.SignerSha256 -eq $identity.SignerSha256

        $session = [ordered]@{
            schemaVersion = 1
            createdAt = (Get-Date).ToString("o")
            serial = $script:DeviceSerial
            device = [ordered]@{
                model = $model
                android = $android
                sdk = $sdk
                build = $build
                securityPatch = $patch
                expectedModel = $script:ExpectedModel
                modelMatches = $model -eq $script:ExpectedModel
            }
            artifact = $identity
            installedArtifact = $installedIdentity
            installAllowed = $installAllowed
        }
        $session | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $sessionDirectory "session.json") -Encoding utf8

        $devices = Invoke-NativeCommand -FilePath $script:AdbPath -Arguments @("devices", "-l")
        Set-Content -LiteralPath (Join-Path $sessionDirectory "adb-devices.txt") -Value $devices.Output -Encoding utf8
        Write-AdbEvidence -SessionDirectory $sessionDirectory -FileName "shizuku-process.txt" -Arguments @("shell", "pidof", "moe.shizuku.privileged.api") | Out-Null
        Save-DeviceSnapshot -SessionDirectory $sessionDirectory -Label "preinstall"

        "Validation session: $sessionDirectory"
        "Device: $model Android $android SDK $sdk build $build patch $patch"
        "Artifact: $($identity.PackageName) $($identity.VersionName) ($($identity.VersionCode))"
        "Artifact signer: $($identity.SignerSha256)"
        if ($null -eq $installedIdentity) {
            "Installed package: not installed"
        } else {
            "Installed signer: $($installedIdentity.SignerSha256)"
        }
        "Install allowed without uninstall: $installAllowed"

        if ($model -ne $script:ExpectedModel) {
            throw "Connected model '$model' does not match expected '$($script:ExpectedModel)'."
        }
        if (-not $installAllowed) {
            throw "Installed package signer does not match the release artifact. No install was attempted."
        }
    }

    "StartCapture" {
        $sessionDirectory = Get-ValidationSessionDirectory
        $session = Get-SessionData -SessionDirectory $sessionDirectory
        $script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $session.serial

        $pidFile = Join-Path $sessionDirectory "logcat.pid"
        if (Test-Path -LiteralPath $pidFile) {
            $oldPid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
            if (Get-Process -Id $oldPid -ErrorAction SilentlyContinue) {
                throw "Logcat capture is already running as PID $oldPid."
            }
        }

        Invoke-AdbCommand -Arguments @("logcat", "-c") | Out-Null
        $stdout = Join-Path $sessionDirectory "logcat-full.txt"
        $stderr = Join-Path $sessionDirectory "logcat-stderr.txt"
        $capture = Start-Process -FilePath $script:AdbPath `
            -ArgumentList @("-s", $script:DeviceSerial, "logcat", "-v", "threadtime") `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -WindowStyle Hidden `
            -PassThru
        Set-Content -LiteralPath $pidFile -Value $capture.Id -Encoding ascii
        "Logcat capture armed: PID $($capture.Id)"
        "Output: $stdout"
    }

    "Install" {
        $sessionDirectory = Get-ValidationSessionDirectory
        $session = Get-SessionData -SessionDirectory $sessionDirectory
        $script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $session.serial
        if (-not [bool]$session.installAllowed) {
            throw "This session recorded an incompatible installed signer. Resolve it and run -Action NewSession again."
        }
        Assert-CaptureRunning -SessionDirectory $sessionDirectory | Out-Null

        $ApkPath = $session.artifact.Path
        $identity = Assert-ReleaseArtifact
        if ($identity.FileSha256 -ne $session.artifact.FileSha256) {
            throw "Release APK changed after preflight. Run -Action NewSession again."
        }
        $installedIdentity = Get-InstalledApkIdentity -SessionDirectory $sessionDirectory -Suffix "install-check"
        if ($null -ne $installedIdentity -and $installedIdentity.SignerSha256 -ne $identity.SignerSha256) {
            throw "Installed signer changed or is incompatible. No install was attempted."
        }

        $install = Invoke-AdbCommand -Arguments @("install", "-r", $identity.Path)
        Set-Content -LiteralPath (Join-Path $sessionDirectory "install.txt") -Value $install.Output -Encoding utf8

        $notificationGrant = Invoke-AdbCommand -Arguments @(
            "shell", "pm", "grant", $script:PackageName, "android.permission.POST_NOTIFICATIONS"
        ) -AllowFailure
        Set-Content -LiteralPath (Join-Path $sessionDirectory "grant-notifications.txt") -Value $notificationGrant.Output -Encoding utf8

        $shizukuGrant = Invoke-AdbCommand -Arguments @(
            "shell", "pm", "grant", $script:PackageName, "moe.shizuku.manager.permission.API_V23"
        ) -AllowFailure
        Set-Content -LiteralPath (Join-Path $sessionDirectory "grant-shizuku.txt") -Value $shizukuGrant.Output -Encoding utf8

        Invoke-AdbCommand -Arguments @("shell", "am", "force-stop", $script:PackageName) | Out-Null
        $installedAfter = Get-InstalledApkIdentity -SessionDirectory $sessionDirectory -Suffix "after"
        if ($null -eq $installedAfter -or $installedAfter.SignerSha256 -ne $identity.SignerSha256) {
            throw "Post-install signer verification failed."
        }

        Save-DeviceSnapshot -SessionDirectory $sessionDirectory -Label "postinstall"
        "Install completed and signer re-verified. App remains stopped."
        "Notification grant exit code: $($notificationGrant.ExitCode)"
        "Shizuku grant exit code: $($shizukuGrant.ExitCode)"
    }

    "Launch" {
        $sessionDirectory = Get-ValidationSessionDirectory
        $session = Get-SessionData -SessionDirectory $sessionDirectory
        $script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $session.serial
        Assert-CaptureRunning -SessionDirectory $sessionDirectory | Out-Null

        $launch = Invoke-AdbCommand -Arguments @(
            "shell", "monkey", "-p", $script:PackageName, "-c", "android.intent.category.LAUNCHER", "1"
        )
        Set-Content -LiteralPath (Join-Path $sessionDirectory "launch.txt") -Value $launch.Output -Encoding utf8
        "Galaxy OptiDroid launched with log capture active."
    }

    "Snapshot" {
        $sessionDirectory = Get-ValidationSessionDirectory
        $session = Get-SessionData -SessionDirectory $sessionDirectory
        $script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $session.serial
        Save-DeviceSnapshot -SessionDirectory $sessionDirectory -Label "manual"
    }

    "StopCapture" {
        $sessionDirectory = Get-ValidationSessionDirectory
        $session = Get-SessionData -SessionDirectory $sessionDirectory
        $script:DeviceSerial = Resolve-DeviceSerial -RequestedSerial $session.serial
        $capturePid = Assert-CaptureRunning -SessionDirectory $sessionDirectory
        $process = Get-Process -Id $capturePid -ErrorAction Stop
        if ($process.ProcessName -ne "adb") {
            throw "Refusing to stop non-adb process $capturePid ($($process.ProcessName))."
        }
        Stop-Process -Id $capturePid
        Wait-Process -Id $capturePid -Timeout 10 -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path $sessionDirectory "logcat.pid") -Force

        $fullLog = Join-Path $sessionDirectory "logcat-full.txt"
        $filteredLog = Join-Path $sessionDirectory "logcat-app-filtered.txt"
        $pattern = "FATAL EXCEPTION|ANR in|AndroidRuntime|am_crash|Application Not Responding|" +
            [regex]::Escape($script:PackageName) +
            "|ShellService|ShizukuShellClient|ShizukuBatchExecutor|OptiDroidWorkerNotify|" +
            "AdbShellClientShizuku|ShizukuSetupViewModel|WM-WorkerWrapper|WorkManager"

        if (Test-Path -LiteralPath $fullLog) {
            Select-String -Path $fullLog -Pattern $pattern |
                ForEach-Object { $_.Line } |
                Set-Content -LiteralPath $filteredLog -Encoding utf8
        }

        $fatalCount = @(Select-String -Path $fullLog -Pattern "FATAL EXCEPTION|am_crash" -ErrorAction SilentlyContinue).Count
        $anrCount = @(Select-String -Path $fullLog -Pattern "ANR in|Application Not Responding" -ErrorAction SilentlyContinue).Count
        $binderCount = @(Select-String -Path $fullLog -Pattern "FAILED BINDER TRANSACTION|TransactionTooLargeException|DeadObjectException" -ErrorAction SilentlyContinue).Count
        $summary = [ordered]@{
            stoppedAt = (Get-Date).ToString("o")
            fatalOrCrashLines = $fatalCount
            anrLines = $anrCount
            binderFailureLines = $binderCount
            fullLog = $fullLog
            filteredLog = $filteredLog
        }
        $summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $sessionDirectory "capture-summary.json") -Encoding utf8
        Save-DeviceSnapshot -SessionDirectory $sessionDirectory -Label "final"
        $summary | Format-List
    }

    "Status" {
        $sessionDirectory = Get-ValidationSessionDirectory
        $session = Get-SessionData -SessionDirectory $sessionDirectory
        "Session: $sessionDirectory"
        "Serial: $($session.serial)"
        "Install allowed: $($session.installAllowed)"
        $pidFile = Join-Path $sessionDirectory "logcat.pid"
        if (Test-Path -LiteralPath $pidFile) {
            $capturePid = [int](Get-Content -LiteralPath $pidFile -Raw).Trim()
            $capture = Get-Process -Id $capturePid -ErrorAction SilentlyContinue
            "Capture running: $($null -ne $capture -and $capture.ProcessName -eq 'adb')"
            "Capture PID: $capturePid"
        } else {
            "Capture running: False"
        }
    }
}
