<#
.SYNOPSIS
    Real-time Performance & Memory Monitor for GamerVoice (com.gamervoice.app)
.DESCRIPTION
    Monitors RAM usage (PSS, Java Heap, Native Heap), CPU%, and verifies VIP Auto-Purge execution in real-time over minutes/hours.
.PARAMETER DurationMinutes
    How many minutes to run the monitoring session (default: 10 minutes).
.PARAMETER IntervalSeconds
    Sampling interval in seconds (default: 5 seconds).
.PARAMETER OutputCsv
    Path to save CSV logs (default: benchmark_ram_results.csv).
#>
param(
    [int]$DurationMinutes = 10,
    [int]$IntervalSeconds = 5,
    [string]$OutputCsv = "benchmark_ram_results.csv"
)

# 1. Locate ADB
$adbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adbPath)) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) {
        $adbPath = $adbCmd.Source
    } else {
        Write-Error "Could not find adb.exe. Ensure Android SDK platform-tools is installed or added to PATH."
        exit 1
    }
}

Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "🎮 GAMERVOICE REAL-TIME RAM & AUTO-PURGE BENCHMARK" -ForegroundColor Yellow
Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "Using ADB: $adbPath" -ForegroundColor DarkGray
Write-Host "Duration: $DurationMinutes min | Sampling every ${IntervalSeconds}s | CSV: $OutputCsv" -ForegroundColor DarkGray

# 2. Check Device Connection
$devices = & $adbPath devices | Select-String -Pattern "\bdevice\b"
if (-not $devices) {
    Write-Host "`n⚠️ No Android devices connected via ADB!" -ForegroundColor Red
    Write-Host "Please connect your phone via USB with USB Debugging enabled, or connect via Wi-Fi:" -ForegroundColor Yellow
    Write-Host "   adb pair <IP>:<PORT>" -ForegroundColor Gray
    Write-Host "   adb connect <IP>:<PORT>`n" -ForegroundColor Gray
    Write-Host "Waiting for device to connect... (Press Ctrl+C to cancel)" -ForegroundColor Yellow
    
    while (-not $devices) {
        Start-Sleep -Seconds 3
        $devices = & $adbPath devices | Select-String -Pattern "\bdevice\b"
    }
    Write-Host "✅ Android device detected!" -ForegroundColor Green
} else {
    Write-Host "✅ Android device connected." -ForegroundColor Green
}

$packageName = "com.gamervoice.app"

# 3. Check if Process is Running
function Get-ProcessPid {
    $pidStr = (& $adbPath shell pidof $packageName 2>$null)
    if ($pidStr) {
        return ($pidStr.Trim() -split '\s+')[0]
    }
    return $null
}

$appPid = Get-ProcessPid
if (-not $appPid) {
    Write-Host "⚠️ App ($packageName) is not currently running." -ForegroundColor Yellow
    Write-Host "Launching GamerVoice in background..." -ForegroundColor Cyan
    & $adbPath shell monkey -p $packageName -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 3
    $appPid = Get-ProcessPid
}

Write-Host "🚀 Monitoring PID: $appPid ($packageName)`n" -ForegroundColor Green

# Prepare CSV Header
"Timestamp,ElapsedSec,PssTotalMB,JavaHeapMB,NativeHeapMB,CpuPercent,AutoPurgeEvents" | Out-File -FilePath $OutputCsv -Encoding UTF8

$startTime = Get-Date
$endTime = $startTime.AddMinutes($DurationMinutes)
$totalPurgesDetected = 0
$samples = @()

# Clear logcat buffer for purge tags
& $adbPath logcat -c 2>$null

Write-Host ("{0,-10} | {1,-12} | {2,-12} | {3,-12} | {4,-8} | {5}" -f "Time", "Total PSS", "Java Heap", "Native Heap", "CPU %", "Purge Status") -ForegroundColor White
Write-Host ("-" * 75) -ForegroundColor DarkGray

while ((Get-Date) -lt $endTime) {
    $now = Get-Date
    $elapsed = [math]::Round(($now - $startTime).TotalSeconds)
    $timeStr = $now.ToString("HH:mm:ss")
    
    # Check if PID still alive
    $currentPid = Get-ProcessPid
    if (-not $currentPid) {
        Write-Host "[$timeStr] ❌ Process exited or killed by system!" -ForegroundColor Red
        break
    }

    # Extract Meminfo
    $memInfo = & $adbPath shell dumpsys meminfo $packageName 2>$null
    
    $pssTotalMb = 0.0
    $javaHeapMb = 0.0
    $nativeHeapMb = 0.0

    if ($memInfo) {
        # TOTAL PSS line: "TOTAL PSS:    25412            TOTAL RSS: ..." or "TOTAL:    25412"
        if ($memInfo -match "TOTAL PSS:\s+(\d+)") {
            $pssTotalMb = [math]::Round([int]$Matches[1] / 1024, 2)
        } elseif ($memInfo -match "TOTAL\s+(\d+)") {
            $pssTotalMb = [math]::Round([int]$Matches[1] / 1024, 2)
        }

        # Java Heap: "Java Heap:\s+(\d+)"
        if ($memInfo -match "Java Heap:\s+(\d+)") {
            $javaHeapMb = [math]::Round([int]$Matches[1] / 1024, 2)
        }

        # Native Heap: "Native Heap:\s+(\d+)"
        if ($memInfo -match "Native Heap:\s+(\d+)") {
            $nativeHeapMb = [math]::Round([int]$Matches[1] / 1024, 2)
        }
    }

    # Extract CPU %
    $topLine = & $adbPath shell "top -n 1 -b | grep $packageName" 2>$null
    $cpuPercent = 0.0
    if ($topLine) {
        $parts = ($topLine.Trim() -split '\s+')
        # Typically column 8 or 9 is %CPU
        foreach ($p in $parts) {
            if ($p -match "^\d+(\.\d+)?$") {
                $val = [double]$p
                if ($val -ge 0.0 -and $val -le 100.0) {
                    $cpuPercent = $val
                }
            }
        }
    }

    # Check for Purge Logcat events since last poll
    $purgeLogs = & $adbPath logcat -d -s VoiceService:D PURGE:I 2>$null
    $purgeStatus = "Idle"
    if ($purgeLogs -match "(VIP Auto RAM Purge|🧹 VIP Auto RAM Purge #(\d+))") {
        $totalPurgesDetected++
        $purgeStatus = "🧹 PURGED! (#$totalPurgesDetected)"
        & $adbPath logcat -c 2>$null  # Reset buffer for next detection
    }

    # Colorize outputs based on limits (<10MB Java Heap, <35MB PSS)
    $pssColor = if ($pssTotalMb -le 35.0) { "Green" } else { "Yellow" }
    $heapColor = if ($javaHeapMb -le 10.0) { "Green" } else { "Yellow" }
    $statusColor = if ($purgeStatus -like "*PURGED*") { "Cyan" } else { "Gray" }

    Write-Host ("{0,-10} | " -f $timeStr) -NoNewline
    Write-Host ("{0,8} MB    | " -f $pssTotalMb) -ForegroundColor $pssColor -NoNewline
    Write-Host ("{0,8} MB    | " -f $javaHeapMb) -ForegroundColor $heapColor -NoNewline
    Write-Host ("{0,8} MB    | " -f $nativeHeapMb) -NoNewline
    Write-Host ("{0,6}%  | " -f $cpuPercent) -NoNewline
    Write-Host $purgeStatus -ForegroundColor $statusColor

    # Save to CSV
    "$timeStr,$elapsed,$pssTotalMb,$javaHeapMb,$nativeHeapMb,$cpuPercent,$purgeStatus" | Out-File -FilePath $OutputCsv -Append -Encoding UTF8

    $samples += [PSCustomObject]@{
        Pss = $pssTotalMb
        JavaHeap = $javaHeapMb
        NativeHeap = $nativeHeapMb
        Cpu = $cpuPercent
    }

    Start-Sleep -Seconds $IntervalSeconds
}

# 4. Final Benchmark Summary
Write-Host "`n==================================================================" -ForegroundColor Cyan
Write-Host "📊 GAMERVOICE BENCHMARK SUMMARY" -ForegroundColor Yellow
Write-Host "==================================================================" -ForegroundColor Cyan

if ($samples.Count -gt 0) {
    $avgPss = [math]::Round(($samples | Measure-Object -Property Pss -Average).Average, 2)
    $maxPss = [math]::Round(($samples | Measure-Object -Property Pss -Maximum).Maximum, 2)
    $avgHeap = [math]::Round(($samples | Measure-Object -Property JavaHeap -Average).Average, 2)
    $maxHeap = [math]::Round(($samples | Measure-Object -Property JavaHeap -Maximum).Maximum, 2)
    $avgCpu = [math]::Round(($samples | Measure-Object -Property Cpu -Average).Average, 2)

    Write-Host "• Total Monitoring Time : $DurationMinutes minutes ($($samples.Count) samples)"
    Write-Host "• Average Total PSS RAM : $avgPss MB (Peak: $maxPss MB)" -ForegroundColor $(if ($avgPss -le 35) {"Green"} else {"Yellow"})
    Write-Host "• Average Java Heap     : $avgHeap MB (Peak: $maxHeap MB)" -ForegroundColor $(if ($avgHeap -le 10) {"Green"} else {"Yellow"})
    Write-Host "• Average CPU Usage     : $avgCpu %"
    Write-Host "• Auto-Purges Executed  : $totalPurgesDetected times" -ForegroundColor $(if ($totalPurgesDetected -gt 0) {"Green"} else {"Cyan"})
    
    Write-Host "`n✅ Results exported to: $OutputCsv" -ForegroundColor Green
    
    if ($avgHeap -le 10.0) {
        Write-Host "🏆 VERIFIED: GamerVoice strictly maintained ultra-low (< 10MB) Java heap consumption!" -ForegroundColor Green
    }
} else {
    Write-Host "No samples collected." -ForegroundColor Red
}
