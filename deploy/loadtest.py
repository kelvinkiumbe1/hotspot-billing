#!/usr/bin/env python3
"""
How much this instance can actually take.

Written because the honest answer to "how many subscribers does one Zidi hold?"
was "we have not measured", and that answer loses an enterprise deal on the
first call. It drives the endpoints an ISP's day is actually made of -- the
captive portal reading plans, customers checking a pass, the office loading the
subscriber list -- rather than a synthetic hello-world that measures Tomcat.

Standard library only, because the point is that an operator can run it on the
box they are about to buy without installing anything.

    python deploy/loadtest.py --base http://localhost:8081 --seconds 20

The numbers it prints are the ones in deploy/SCALE.md. Re-run it after any
change that touches a hot query and put the new ones there.
"""

import argparse
import base64
import json
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import defaultdict


class Endpoint:
    """One request shape, and what it stands for in a real day."""

    def __init__(self, name, path, why, auth=False, method="GET", body=None):
        self.name = name
        self.path = path
        self.why = why
        self.auth = auth
        self.method = method
        self.body = body


# Weighted towards what actually happens: a captive portal is hit far more often
# than an admin page, and getting that ratio wrong measures the wrong thing.
ENDPOINTS = [
    Endpoint("portal-plans", "/api/plans",
             "every customer who opens the captive portal"),
    Endpoint("portal-settings", "/api/portal-settings",
             "loaded once per portal visit, before anything renders"),
    Endpoint("status-page", "/api/status",
             "the public status page during an outage, when everyone looks at once"),
    Endpoint("admin-overview", "/api/admin/overview",
             "the first screen every morning", auth=True),
    Endpoint("admin-subscribers", "/api/admin/subscribers",
             "the list the office lives in -- the query that grows with the book",
             auth=True),
    Endpoint("admin-payments", "/api/admin/payments",
             "reconciliation, and the heaviest read in the product", auth=True),
]


def call(base, endpoint, credentials):
    request = urllib.request.Request(
        base + endpoint.path,
        method=endpoint.method,
        data=json.dumps(endpoint.body).encode() if endpoint.body else None,
    )
    if endpoint.body:
        request.add_header("Content-Type", "application/json")
    if endpoint.auth:
        request.add_header("Authorization", "Basic " + credentials)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
            return (time.perf_counter() - started) * 1000, response.status
    except urllib.error.HTTPError as e:
        e.read()
        return (time.perf_counter() - started) * 1000, e.code
    except Exception:
        return (time.perf_counter() - started) * 1000, 0


def run(base, credentials, seconds, workers):
    latencies = defaultdict(list)
    statuses = defaultdict(lambda: defaultdict(int))
    lock = threading.Lock()
    stop_at = time.time() + seconds

    def worker(index):
        # Each worker starts at a different endpoint so they do not all hammer
        # the same query in lockstep, which would measure one cache rather than
        # the system.
        i = index
        while time.time() < stop_at:
            endpoint = ENDPOINTS[i % len(ENDPOINTS)]
            millis, status = call(base, endpoint, credentials)
            with lock:
                latencies[endpoint.name].append(millis)
                statuses[endpoint.name][status] += 1
            i += 1

    threads = [threading.Thread(target=worker, args=(n,), daemon=True)
               for n in range(workers)]
    started = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - started
    return latencies, statuses, elapsed


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    at = min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))
    return ordered[at]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8081")
    parser.add_argument("--user", default="admin")
    parser.add_argument("--password", default="admin123")
    parser.add_argument("--seconds", type=int, default=20)
    parser.add_argument("--workers", type=int, default=24)
    parser.add_argument("--label", default="")
    args = parser.parse_args()

    credentials = base64.b64encode(
        f"{args.user}:{args.password}".encode()).decode()

    # One pass first, so a misconfigured run fails in a second rather than after
    # the full duration.
    for endpoint in ENDPOINTS:
        _, status = call(args.base, endpoint, credentials)
        if status not in (200, 201):
            print(f"!! {endpoint.name} answered {status} -- fix that before measuring",
                  file=sys.stderr)
            return 1

    print(f"Driving {args.base} with {args.workers} workers for {args.seconds}s"
          + (f"  [{args.label}]" if args.label else ""))
    latencies, statuses, elapsed = run(
        args.base, credentials, args.seconds, args.workers)

    total = sum(len(v) for v in latencies.values())
    print(f"\n{total} requests in {elapsed:.1f}s "
          f"= {total / elapsed:,.0f} req/s across all endpoints\n")
    print(f"{'endpoint':<20}{'n':>7}{'p50':>8}{'p95':>8}{'p99':>8}{'max':>8}   non-200")
    print("-" * 74)
    for endpoint in ENDPOINTS:
        values = latencies[endpoint.name]
        if not values:
            continue
        bad = sum(c for s, c in statuses[endpoint.name].items() if s not in (200, 201))
        print(f"{endpoint.name:<20}{len(values):>7}"
              f"{percentile(values, 50):>8.0f}{percentile(values, 95):>8.0f}"
              f"{percentile(values, 99):>8.0f}{max(values):>8.0f}"
              f"   {bad if bad else '-'}")
    print("\nmilliseconds. p95 is the number to hold yourself to; p50 flatters everything.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
