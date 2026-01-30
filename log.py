#!/usr/bin/env python3
import subprocess
import sys
import time
import threading
import re

# Tags you want to highlight
FILTER_TAGS = [
    "LLMServer",
    "JNI",
    "Vulkan",
    "DownloadFlow",
    "PythonServer",
    "AndroidRuntime",
]

# Color map for tags
COLORS = {
    "LLMServer": "\033[92m",       # green
    "JNI": "\033[96m",             # cyan
    "Vulkan": "\033[95m",          # magenta
    "DownloadFlow": "\033[94m",    # blue
    "PythonServer": "\033[93m",    # yellow
    "AndroidRuntime": "\033[91m",  # red
}

RESET = "\033[0m"


def colorize(line):
    for tag in FILTER_TAGS:
        if tag in line:
            color = COLORS.get(tag, "")
            return f"{color}{line}{RESET}"
    return line


def start_logcat():
    # Build the filter command
    filter_args = " ".join([f"-s {tag}" for tag in FILTER_TAGS])
    cmd = f"adb logcat {filter_args}"

    while True:
        try:
            print("\n=== Live Logcat (Ctrl+C to exit) ===\n")
            process = subprocess.Popen(
                cmd,
                shell=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
            )

            for line in process.stdout:
                if line.strip():
                    print(colorize(line.rstrip()))

        except KeyboardInterrupt:
            print("\nStopping logcat viewer.")
            sys.exit(0)

        except Exception as e:
            print(f"Logcat crashed: {e}")
            print("Reconnecting in 2 seconds...")
            time.sleep(2)


if __name__ == "__main__":
    start_logcat()

