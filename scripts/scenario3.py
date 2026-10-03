#!/usr/bin/env python3
"""Switch the Scenario 3 workload without changing Identity source or image."""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAB = ROOT / "scripts/lab.sh"
RATE = "30"


def run(*args):
    subprocess.run([str(LAB), *args], cwd=ROOT, check=True)


def stop_if_running():
    status = subprocess.run([str(LAB), "traffic", "status"], cwd=ROOT,
                            capture_output=True, text=True)
    if status.returncode == 0 and "state=running" in status.stdout:
        run("traffic", "stop")


def main():
    action = sys.argv[1] if len(sys.argv) == 2 else ""
    if action == "baseline":
        run("traffic", "start", "--profile", "identity", "--rate", RATE, "--login-percent", "5")
    elif action == "activate":
        stop_if_running()
        run("traffic", "start", "--profile", "identity", "--rate", RATE, "--login-percent", "70")
        print("Workload rollout applied")
    elif action == "restore":
        stop_if_running()
        print("Scenario 3 traffic stopped")
    else:
        raise SystemExit("Usage: scenario3.py baseline|activate|restore")


if __name__ == "__main__":
    main()
