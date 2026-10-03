#!/usr/bin/env python3
"""Check the prebuilt Identity exercise under light and heavy login mixes."""

import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAB = ROOT / "scripts/lab.sh"
SOURCE = ROOT / "backend/services/identity-service/src/main/scala/org/typelevel/video/streaming/backend/identity/PasswordHasher.scala"


def sample(login_percent):
    last = None
    for attempt in range(3):
        result = subprocess.run(
            [str(LAB), "traffic", "run", "--profile", "identity", "--rate", "30",
             "--duration", "25s", "--login-percent", str(login_percent)],
            cwd=ROOT, text=True, capture_output=True,
        )
        reports = [json.loads(line) for line in result.stdout.splitlines() if line.startswith("{")]
        summary = next((report for report in reports if report.get("type") == "summary"), None)
        if summary is None:
            raise RuntimeError(f"Generator produced no summary: {result.stderr}")
        last = summary
        if summary["load_valid"] and summary["failed"] == 0 and summary["succeeded"] >= 700:
            return summary
        print(f"Retrying {login_percent}% mix after invalid load ({attempt + 1}/3)", flush=True)
    raise RuntimeError(f"Traffic remained invalid: {last}")


def main():
    verify = SOURCE.read_text().split("override def verify", 1)[1]
    if "IO.delay {" not in verify:
        raise RuntimeError("The checked-out Identity source is not the exercise version")
    baseline = sample(5)
    fault = sample(70)
    light = lambda s: s["operations"]["identity-current-user"]["latency_mean_ms"]
    before, after = light(baseline), light(fault)
    print(f"Current-user mean latency: {before:.1f} ms baseline, {after:.1f} ms heavy mix")
    if after < before * 2:
        raise RuntimeError("The unrelated Identity slowdown was too small on this machine")
    print("Scenario 3 workload check passed")


if __name__ == "__main__":
    main()
