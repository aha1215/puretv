param(
    [string]$Serial = "192.168.0.110:5555",
    [string[]]$Methods = @("savedVodPlaybackWithChat", "savedVodRepeatPlaybackWithChat")
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = (Get-Command adb -ErrorAction Stop).Source
$javaHome = "C:\Users\inkba\AppData\Local\Programs\Android Studio\jbr"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
$gradlew = Join-Path $repo "gradlew.bat"
$packageName = "com.puretv.twitch.tv"
$testPackage = "com.puretv.twitch.tv.benchmark"
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$targetApk = Join-Path $repo "app-tv\build\outputs\apk\full\benchmark\app-tv-full-benchmark.apk"
$runnerApk = Join-Path $repo "benchmark-tv\build\outputs\apk\benchmark\benchmark-tv-benchmark.apk"
$resultsDir = Join-Path $repo "benchmark-tv\build\device-results"
$runDir = Join-Path $env:TEMP ("puretv-smb-benchmark-" + [guid]::NewGuid().ToString("N"))
$snapshotHelperApk = Join-Path $runDir "settings-snapshot-helper.apk"
$snapshotPath = Join-Path $runDir "puretv_tv_settings.preferences_pb"
$originalApk = Join-Path $runDir "original-tv.apk"
$targetPushed = $false
$settingsCaptured = $false
$originalCaptured = $false
$settingsRestored = $false
$originalRestored = $false

New-Item -ItemType Directory -Path $runDir -Force | Out-Null
New-Item -ItemType Directory -Path $resultsDir -Force | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $output = & $adb -s $Serial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb command failed ($LASTEXITCODE): $($AdbArgs -join ' ')`n$($output -join "`n")" }
    $output
}

function Save-PrivateFile {
    param([string[]]$AdbArgs, [string]$Path)
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo.FileName = $adb
    $process.StartInfo.Arguments = "-s `"$Serial`" $($AdbArgs -join ' ')"
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    if (-not $process.Start()) { throw "Could not start adb snapshot command" }
    $file = [System.IO.File]::Create($Path)
    try { $process.StandardOutput.BaseStream.CopyTo($file) } finally { $file.Dispose() }
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Could not snapshot app settings: $stderr" }
}

function Run-BenchmarkMethod {
    param([string]$Method)
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $logPath = Join-Path $resultsDir "$stamp-$Method.txt"
    Write-Host "Running $Method against $Serial; output: $logPath"
    $output = & $adb -s $Serial shell am instrument -w -r `
        -e targetPackage $packageName `
        -e class "com.puretv.twitch.tv.benchmark.TvExperienceBenchmark#$Method" `
        $runner 2>&1
    $exitCode = $LASTEXITCODE
    $output | Set-Content -LiteralPath $logPath
    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0 -or ($output -join "`n") -match "FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2") {
        throw "Benchmark method $Method failed; see $logPath"
    }
    $traceDir = Join-Path $resultsDir "$stamp-$Method-traces"
    New-Item -ItemType Directory -Path $traceDir -Force | Out-Null
    & $adb -s $Serial pull "/sdcard/Android/media/$testPackage" $traceDir | Out-Null
}

try {
    $deviceState = (& $adb -s $Serial get-state 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or $deviceState -ne "device") { throw "ADB device $Serial is not available" }

    Push-Location $repo
    try {
        & $gradlew :app-tv:assembleRelease :app-tv:assembleBenchmark :benchmark-tv:assembleBenchmark `
            -PbenchmarkAgainstInstalledApp=true -PbenchmarkSnapshotHelper=true
        if ($LASTEXITCODE -ne 0) { throw "Could not build the signed TV benchmark APKs" }
        Copy-Item -LiteralPath $targetApk -Destination $snapshotHelperApk
        & $gradlew :app-tv:assembleBenchmark -PbenchmarkAgainstInstalledApp=true -PbenchmarkSnapshotHelper=false
        if ($LASTEXITCODE -ne 0) { throw "Could not build the non-debuggable benchmark target" }
    } finally {
        Pop-Location
    }

    $installedPath = (& $adb -s $Serial shell pm path $packageName | Select-Object -First 1).Trim()
    if (-not $installedPath.StartsWith("package:")) { throw "The configured TV app is not installed on $Serial" }
    $deviceApkPath = $installedPath.Substring("package:".Length)
    Invoke-Adb pull $deviceApkPath $originalApk | Out-Null
    $originalCaptured = $true

    Invoke-Adb push $snapshotHelperApk /data/local/tmp/puretv-tv-settings-helper.apk | Out-Null
    Invoke-Adb shell pm install -r /data/local/tmp/puretv-tv-settings-helper.apk | Out-Null
    $targetPushed = $true

    $debuggable = (& $adb -s $Serial shell run-as $packageName id 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $debuggable -notmatch "uid=") {
        throw "Signed benchmark target did not preserve debuggable run-as access; refusing to run without a restorable settings snapshot"
    }
    Save-PrivateFile @("exec-out", "run-as", $packageName, "cat", "files/datastore/puretv_tv_settings.preferences_pb") $snapshotPath
    if ((Get-Item -LiteralPath $snapshotPath).Length -lt 16) { throw "The app settings snapshot is missing or too small" }
    $settingsCaptured = $true

    Invoke-Adb push $targetApk /data/local/tmp/puretv-tv-benchmark.apk | Out-Null
    Invoke-Adb shell pm install -r /data/local/tmp/puretv-tv-benchmark.apk | Out-Null

    Invoke-Adb push $runnerApk /data/local/tmp/puretv-tv-benchmark-runner.apk | Out-Null
    Invoke-Adb shell pm install -r /data/local/tmp/puretv-tv-benchmark-runner.apk | Out-Null
    foreach ($method in $Methods) { Run-BenchmarkMethod $method }
}
finally {
    if ($settingsCaptured -and $targetPushed) {
        & $adb -s $Serial shell am force-stop $packageName | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not stop the benchmark app before restoring its settings"
        } else {
            & $adb -s $Serial shell pm install -r /data/local/tmp/puretv-tv-settings-helper.apk | Out-Null
            if ($LASTEXITCODE -ne 0) { Write-Warning "Could not install settings helper to restore the saved VOD position" }
            & $adb -s $Serial push $snapshotPath /data/local/tmp/puretv_tv_settings.preferences_pb | Out-Null
            if ($LASTEXITCODE -eq 0) {
                & $adb -s $Serial shell run-as $packageName cp /data/local/tmp/puretv_tv_settings.preferences_pb files/datastore/puretv_tv_settings.preferences_pb | Out-Null
                if ($LASTEXITCODE -eq 0) { $settingsRestored = $true }
                else { Write-Warning "Could not restore the saved VOD position from the DataStore snapshot" }
            } else {
                Write-Warning "Could not push the DataStore snapshot back to the device"
            }
        }
    }
    if ($originalCaptured) {
        & $adb -s $Serial push $originalApk /data/local/tmp/puretv-tv-original.apk | Out-Null
        if ($LASTEXITCODE -eq 0) {
            & $adb -s $Serial shell pm install -r /data/local/tmp/puretv-tv-original.apk | Out-Null
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not restore the exact app APK. The settings snapshot was retained at $snapshotPath"
        } else {
            $originalRestored = $true
            if ($settingsRestored) { Write-Host "Restored the original TV APK and saved settings (including VOD position)." }
            else { Write-Warning "The original TV APK was restored, but saved settings restoration was not confirmed; snapshot retained at $snapshotPath" }
        }
    }
    & $adb -s $Serial shell pm uninstall $testPackage | Out-Null
    & $adb -s $Serial shell rm -f /data/local/tmp/puretv-tv-benchmark.apk /data/local/tmp/puretv-tv-original.apk /data/local/tmp/puretv-tv-settings-helper.apk /data/local/tmp/puretv-tv-benchmark-runner.apk /data/local/tmp/puretv_tv_settings.preferences_pb | Out-Null
    if ($originalRestored -and $settingsRestored) { Remove-Item -LiteralPath $runDir -Recurse -Force }
}
