#!/usr/bin/env python3
"""Black-box baseline, fault, participant rollback, and recovery check."""

import argparse
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAB = ROOT / "scripts/lab.sh"
PLATFORM = ROOT / "bin/platform"
CONTAINER = "typelevel-video-streaming-lab-traffic"

METRICS = {
    "offered": 'sum(rate(lab_traffic_arrivals_total{operation="catalog-courses"}[30s]))',
    "sent": 'sum(rate(lab_traffic_arrivals_total{operation="catalog-courses",result="sent"}[30s]))',
    "gateway_rate": 'sum(rate(http_server_request_duration_seconds_count{service_name="gateway-service",classifier="/api/catalog/courses",http_phase="body"}[30s]))',
    "client_rate": 'sum(rate(http_client_request_duration_seconds_count{service_name="gateway-service",classifier="catalog:/courses",http_phase="body"}[30s]))',
    "catalog_rate": 'sum(rate(http_server_request_duration_seconds_count{service_name="catalog-service",classifier="/courses",http_phase="body"}[30s]))',
    "gateway_p95": 'histogram_quantile(0.95,sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="gateway-service",classifier="/api/catalog/courses",http_phase="body"}[30s])))',
    "client_p95": 'histogram_quantile(0.95,sum by (le) (rate(http_client_request_duration_seconds_bucket{service_name="gateway-service",classifier="catalog:/courses",http_phase="body"}[30s])))',
    "catalog_p95": 'histogram_quantile(0.95,sum by (le) (rate(http_server_request_duration_seconds_bucket{service_name="catalog-service",classifier="/courses",http_phase="body"}[30s])))',
}


def command(*args):
    return subprocess.run(args, cwd=ROOT, check=True, text=True, capture_output=True).stdout


def get_json(url, params=None):
    if params:
        url += "?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.load(response)


def metric(grafana, query):
    data = get_json(
        grafana + "/api/datasources/proxy/uid/prometheus/api/v1/query", {"query": query}
    )
    values = data["data"]["result"]
    if len(values) != 1:
        raise AssertionError(f"expected one metric series, got {len(values)}: {query}")
    return float(values[0]["value"][1])


def latest_report():
    lines = command("docker", "logs", "--tail", "10", CONTAINER).splitlines()
    return next(json.loads(line) for line in reversed(lines) if '"type":"progress"' in line)


def observe(name, grafana, rate, window):
    time.sleep(window)
    values = {key: metric(grafana, query) for key, query in METRICS.items()}
    report = latest_report()
    print(json.dumps({"phase": name, "metrics": values, "generator": report}), flush=True)
    assert report["load_valid"] and report["failed"] == 0, "generator lost or failed requests"
    for key in ("offered", "sent", "gateway_rate", "client_rate", "catalog_rate"):
        assert rate * 0.8 <= values[key] <= rate * 1.2, f"{name}: {key} rate diverged"
    return values


def boundary_trace(grafana, start, slow):
    search = get_json(
        grafana + "/api/datasources/proxy/uid/tempo/api/search",
        {
            "q": '{resource.service.name="gateway-service" && duration '
                 + ("> 500ms}" if slow else "< 100ms}"),
            "start": start,
            "end": int(time.time()),
            "limit": 10,
        },
    )
    for candidate in search.get("traces", []):
        trace_id = candidate["traceID"]
        trace = get_json(grafana + "/api/datasources/proxy/uid/tempo/api/traces/" + trace_id)
        spans = []
        for batch in trace["batches"]:
            service = next(
                (a["value"].get("stringValue") for a in batch["resource"]["attributes"]
                 if a["key"] == "service.name"), None
            )
            for scope in batch["scopeSpans"]:
                for span in scope["spans"]:
                    spans.append((service, span, (int(span["endTimeUnixNano"])
                                                  - int(span["startTimeUnixNano"])) / 1e6))
        clients = [(s, ms) for service, s, ms in spans
                   if service == "gateway-service" and s["name"] == "GET /courses"]
        servers = [(s, ms) for service, s, ms in spans
                   if service == "catalog-service" and s["name"] == "GET /courses"]
        boundary_ms = clients[0][1] - servers[0][1] if clients and servers else 0
        if len(clients) == 1 and len(servers) == 1 and \
                servers[0][0].get("parentSpanId") == clients[0][0]["spanId"] and \
                (boundary_ms > 500 if slow else clients[0][1] < 100):
            print(json.dumps({"trace_phase": "fault" if slow else "recovery", "trace_id": trace_id,
                              "client_ms": clients[0][1], "catalog_server_ms": servers[0][1]}),
                  flush=True)
            return
    raise AssertionError("no matching distributed trace for " + ("fault" if slow else "recovery"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--grafana", default="http://localhost:3000")
    parser.add_argument("--rate", type=int, default=5)
    parser.add_argument("--window", type=int, default=40)
    args = parser.parse_args()
    if args.window < 35 or args.rate < 1:
        parser.error("window must be at least 35 seconds and rate must be positive")

    command(str(LAB), "proxy", "reset")
    command(str(LAB), "traffic", "start", "--rate", str(args.rate))
    try:
        baseline = observe("baseline", args.grafana, args.rate, args.window)
        assert baseline["client_p95"] < 0.2 and baseline["gateway_p95"] < 0.2
        fault_start = int(time.time())
        activation = command(str(LAB), "incident", "start", "8f27")
        change_id = activation.strip().split()[-1]
        fault = observe("fault", args.grafana, args.rate, args.window)
        assert fault["client_p95"] > baseline["client_p95"] + 0.4
        assert fault["gateway_p95"] > baseline["gateway_p95"] + 0.4
        assert fault["catalog_p95"] < 0.15
        boundary_trace(args.grafana, fault_start, slow=True)
        command(str(PLATFORM), "rollback", change_id)
        recovery_start = int(time.time())
        recovery = observe("recovery", args.grafana, args.rate, args.window)
        assert recovery["client_p95"] < 0.2 and recovery["gateway_p95"] < 0.2
        assert recovery["catalog_p95"] < 0.15
        boundary_trace(args.grafana, recovery_start, slow=False)
        print("Scenario 1 passed", flush=True)
    finally:
        try:
            command(str(LAB), "traffic", "stop")
        finally:
            command(str(LAB), "proxy", "reset")


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, subprocess.CalledProcessError) as error:
        print(f"Scenario 1 failed: {error}", file=sys.stderr)
        sys.exit(1)
