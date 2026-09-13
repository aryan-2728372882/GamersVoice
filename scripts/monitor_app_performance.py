#!/usr/bin/env python3
"""
GamerVoice (com.gamervoice.app) Background Performance & Auto-Purge Benchmark
Monitors RAM (PSS, Java Heap, Native Heap), CPU%, and tracks Auto-Purge execution.
"""

import os
import sys
import time
import subprocess
import re
import csv
from datetime import datetime

PACKAGE_NAME = "com.gamervoice.app"

def find_adb():
    local_app_data = os.environ.get("LOCALAPPDATA", "")
    default_adb = os.path.join(local_app_data, "Android", "Sdk", "platform-tools", "adb.exe")
    if os.path.exists(default_adb):
        return default_adb
    return "adb"

def run_adb(adb_cmd, args):
    try:
        res = subprocess.run([adb_cmd] + args, capture_output=True, text=True, timeout=10)
        return res.stdout
    except Exception:
        return ""

def main():
    duration_mins = 10
    interval_sec = 5
    if len(sys.argv) > 1:
        try:
            duration_mins = int(sys.argv[1])
        except ValueError:
            pass

    adb = find_adb()
    print("=" * 66)
    print("🎮 GAMERVOICE BENCHMARK MONITOR (Python)")
    print("=" * 66)
    print(f"ADB Binary  : {adb}")
    print(f"Duration    : {duration_mins} minutes (sampling every {interval_sec}s)")
    print(f"Package     : {PACKAGE_NAME}")
    
    # Check device
    devices_out = run_adb(adb, ["devices"])
    if "\tdevice" not in devices_out:
        print("\n⚠️ No ADB device found! Connect your phone with USB debugging or Wi-Fi.")
        print("Waiting for device...")
        while "\tdevice" not in run_adb(adb, ["devices"]):
            time.sleep(3)
        print("✅ Device detected!")

    # Check pid
    pid_out = run_adb(adb, ["shell", "pidof", PACKAGE_NAME]).strip()
    if not pid_out:
        print("⚠️ App is not running. Launching...")
        run_adb(adb, ["shell", "monkey", "-p", PACKAGE_NAME, "-c", "android.intent.category.LAUNCHER", "1"])
        time.sleep(3)

    csv_filename = "benchmark_ram_results.csv"
    with open(csv_filename, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["Timestamp", "ElapsedSec", "PssTotalMB", "JavaHeapMB", "NativeHeapMB", "CpuPercent", "PurgeStatus"])

    run_adb(adb, ["logcat", "-c"])

    print(f"\n{'Time':<10} | {'Total PSS':<12} | {'Java Heap':<12} | {'Native Heap':<12} | {'CPU %':<8} | {'Purge Status'}")
    print("-" * 75)

    start_time = time.time()
    end_time = start_time + (duration_mins * 60)
    purges_count = 0
    samples = []

    while time.time() < end_time:
        now_dt = datetime.now()
        time_str = now_dt.strftime("%H:%M:%S")
        elapsed = int(time.time() - start_time)

        mem_info = run_adb(adb, ["shell", "dumpsys", "meminfo", PACKAGE_NAME])
        if not mem_info or "No process found" in mem_info:
            print(f"[{time_str}] App process terminated.")
            break

        pss_mb = 0.0
        m_pss = re.search(r"TOTAL PSS:\s+(\d+)", mem_info) or re.search(r"TOTAL\s+(\d+)", mem_info)
        if m_pss:
            pss_mb = round(int(m_pss.group(1)) / 1024.0, 2)

        java_heap_mb = 0.0
        m_java = re.search(r"Java Heap:\s+(\d+)", mem_info)
        if m_java:
            java_heap_mb = round(int(m_java.group(1)) / 1024.0, 2)

        native_heap_mb = 0.0
        m_nat = re.search(r"Native Heap:\s+(\d+)", mem_info)
        if m_nat:
            native_heap_mb = round(int(m_nat.group(1)) / 1024.0, 2)

        # CPU%
        top_out = run_adb(adb, ["shell", f"top -n 1 -b | grep {PACKAGE_NAME}"])
        cpu_pct = 0.0
        for token in top_out.split():
            try:
                val = float(token)
                if 0.0 <= val <= 100.0:
                    cpu_pct = val
            except ValueError:
                pass

        # Purge logs
        log_out = run_adb(adb, ["logcat", "-d", "-s", "VoiceService:D", "PURGE:I"])
        purge_status = "Idle"
        if "VIP Auto RAM Purge" in log_out:
            purges_count += 1
            purge_status = f"🧹 PURGED! (#{purges_count})"
            run_adb(adb, ["logcat", "-c"])

        print(f"{time_str:<10} | {pss_mb:>8} MB    | {java_heap_mb:>8} MB    | {native_heap_mb:>8} MB    | {cpu_pct:>6}%  | {purge_status}")

        with open(csv_filename, "a", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([time_str, elapsed, pss_mb, java_heap_mb, native_heap_mb, cpu_pct, purge_status])

        samples.append((pss_mb, java_heap_mb, cpu_pct))
        time.sleep(interval_sec)

    print("\n" + "=" * 66)
    print("📊 BENCHMARK COMPLETE")
    print("=" * 66)
    if samples:
        avg_pss = round(sum(s[0] for s in samples) / len(samples), 2)
        avg_heap = round(sum(s[1] for s in samples) / len(samples), 2)
        avg_cpu = round(sum(s[2] for s in samples) / len(samples), 2)
        print(f"Average Total PSS : {avg_pss} MB")
        print(f"Average Java Heap : {avg_heap} MB")
        print(f"Average CPU       : {avg_cpu} %")
        print(f"Auto-Purges Run   : {purges_count} times")
        print(f"CSV Report Saved  : {csv_filename}")

if __name__ == "__main__":
    main()
