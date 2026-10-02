#!/usr/bin/env python3
"""Local platform change ledger for the workshop's first incident."""

import argparse
import fcntl
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
STATE_DIR = ROOT / ".lab"
STATE = STATE_DIR / "platform-changes.json"

def save(changes):
    temporary = STATE.with_suffix(".tmp")
    temporary.write_text(json.dumps(changes, indent=2) + "\n")
    temporary.replace(STATE)


def proxy(*args):
    result = subprocess.run(
        [str(ROOT / "scripts/lab.sh"), "proxy", *args], cwd=ROOT,
        text=True, capture_output=True,
    )
    if result.returncode:
        print(result.stderr or result.stdout, file=sys.stderr, end="")
        raise subprocess.CalledProcessError(result.returncode, result.args)


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def main():
    if sys.argv[1:2] == ["_activate"]:
        parser = argparse.ArgumentParser()
        parser.add_argument("--milliseconds", type=int, default=750)
        args = parser.parse_args(sys.argv[2:])
        args.command = "_activate"
    else:
        parser = argparse.ArgumentParser(description="Inspect and roll back recent platform changes")
        commands = parser.add_subparsers(dest="command", required=True)
        commands.add_parser("changes")
        inspect = commands.add_parser("inspect")
        inspect.add_argument("change_id")
        rollback = commands.add_parser("rollback")
        rollback.add_argument("change_id")
        args = parser.parse_args()

    STATE_DIR.mkdir(exist_ok=True)
    with (STATE_DIR / "platform.lock").open("a+") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        changes = json.loads(STATE.read_text()) if STATE.exists() else []
        current = next((item for item in changes if item["id"] == getattr(args, "change_id", None)), None)

        if args.command == "_activate":
            if not 1 <= args.milliseconds <= 9999:
                parser.error("milliseconds must be between 1 and 9999")
            if any(item["status"] == "active" for item in changes):
                parser.error("traffic policy is already active; roll it back first")
            proxy("latency", "--milliseconds", str(args.milliseconds))
            change_id = "traffic-policy-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid4().hex[:6]
            change = {
                "id": change_id,
                "title": "East-west traffic policy rollout",
                "applied_at": now(),
                "status": "active",
                "configuration": {"catalog_egress_delay_ms": args.milliseconds},
                "previous_configuration": {"catalog_egress_delay_ms": 0},
            }
            changes.append(change)
            save(changes)
            print(f"Applied {change_id}")
        elif args.command == "changes":
            for item in reversed(changes):
                print(f"{item['id']}  {item['applied_at']}  {item['status']}  {item['title']}")
        else:
            if current is None:
                parser.error("unknown change ID")
            if args.command == "inspect":
                print(json.dumps(current, indent=2))
            elif args.command == "rollback":
                # Reconcile first. A failed proxy call leaves the change active and retryable.
                proxy("reset")
                current["status"] = "rolled_back"
                current["rolled_back_at"] = now()
                save(changes)
                print(f"Rolled back {args.change_id}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
