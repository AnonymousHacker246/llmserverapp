#!/usr/bin/env python3
import subprocess
import sys
import os
import time

PACKAGE = "com.example.llmserverapp"
MAIN_ACTIVITY = ".SplashScreen"

# Customize your log filters here
LOG_FILTERS = [
    "LLMServer",
    "JNI",
    "Vulkan",
    "DownloadFlow",
    "PythonServer",
    "AndroidRuntime",
]

def run(cmd, cwd=None):
    print(f"\n>>> {cmd}")
    process = subprocess.Popen(cmd, shell=True, cwd=cwd)
    process.wait()
    if process.returncode != 0:
        print(f"Command failed with exit code {process.returncode}")
        sys.exit(process.returncode)

def build_app():
    print("\n=== Building APK ===")
    run("./gradlew assembleDebug")

def install_app():
    print("\n=== Installing APK ===")
    apk_path = "app/build/outputs/apk/debug/app-debug.apk"
    if not os.path.exists(apk_path):
        print("APK not found. Did the build succeed?")
        sys.exit(1)
    run(f"adb install -r {apk_path}")

def launch_app():
    print("\n=== Launching App with Monkey ===")
    cmd = (
        f"adb shell monkey "
        f"-p {PACKAGE} "
        f"-c android.intent.category.LAUNCHER "
        f"1"
    )
    run(cmd)

def stream_logs():
    print("\n=== Streaming Logcat ===")
    filter_str = " ".join([f"-s {tag}" for tag in LOG_FILTERS])
    print(f"Using filters: {', '.join(LOG_FILTERS)}\n")
    os.system(f"adb logcat {filter_str}")

def main():
    build_app()
    install_app()
    launch_app()
    time.sleep(1)
    stream_logs()

if __name__ == "__main__":
    main()

