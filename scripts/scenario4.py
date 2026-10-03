#!/usr/bin/env python3
"""Seed stable Playback actors and switch their request mix."""

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAB = ROOT / "scripts/lab.sh"
RATE = "10"
PASSWORD = "lab-playback-password-2026"


def run(*args, capture=False):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          capture_output=capture)


def lab(*args):
    run(str(LAB), *args)


def playback_mode(enabled):
    env = os.environ.copy()
    env["PLAYBACK_WORKSHOP_READ_MODE"] = "true" if enabled else "false"
    subprocess.run([str(LAB), "rebuild", "playback-service"], cwd=ROOT,
                   check=True, env=env)


def stop_if_running():
    status = subprocess.run([str(LAB), "traffic", "status"], cwd=ROOT,
                            capture_output=True, text=True)
    if status.returncode == 0 and "state=running" in status.stdout:
        lab("traffic", "stop")


def prepare():
    playback_mode(True)
    for actor in range(10):
        kind = "old" if actor < 8 else "new"
        email = f"lab-playback-{kind}-{actor}@example.invalid"
        body = json.dumps({"email": email, "password": PASSWORD,
                           "displayName": "Playback Lab Actor"}).encode()
        request = urllib.request.Request(
            "http://localhost:8085/api/identity/users", data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                if response.status != 201:
                    raise RuntimeError(f"registration returned {response.status}")
        except urllib.error.HTTPError as error:
            if error.code != 409:
                raise

        result = run("docker", "compose", "exec", "-T", "postgres", "psql",
                     "-U", "postgres", "-d", "identity", "-At", "-c",
                     f"SELECT id FROM users WHERE email = '{email}'", capture=True)
        user_id = uuid.UUID(result.stdout.strip())
        event_id = uuid.uuid5(uuid.NAMESPACE_URL, f"playback-lab:{user_id}")
        run("docker", "compose", "exec", "-T", "postgres", "psql",
            "-U", "postgres", "-d", "playback", "-q", "-c",
            "INSERT INTO users (id, event_id, created_at) "
            f"VALUES ('{user_id}', '{event_id}', now()) ON CONFLICT (id) DO NOTHING")
    print("Ten actors registered; Playback user projections seeded")


def main():
    action = sys.argv[1] if len(sys.argv) == 2 else ""
    if action == "prepare":
        prepare()
    elif action == "baseline":
        stop_if_running()
        lab("traffic", "start", "--profile", "playback", "--rate", RATE,
            "--modern-percent", "0")
    elif action == "activate":
        stop_if_running()
        lab("traffic", "start", "--profile", "playback", "--rate", RATE,
            "--modern-percent", "20")
        print("Workload rollout applied")
    elif action == "restore":
        stop_if_running()
        playback_mode(False)
        print("Scenario 4 traffic stopped")
    else:
        raise SystemExit("Usage: scenario4.py prepare|baseline|activate|restore")


if __name__ == "__main__":
    main()
