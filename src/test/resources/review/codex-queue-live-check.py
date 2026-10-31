#!/usr/bin/env python3
"""Opt-in D1 compatibility check. Starts ONE real model turn; never run in CI.

Requires explicit user authorization as well as SESHLOG_DIFF_REVIEW_LIVE=1.
The only sent text is MESSAGE. Output contains aggregate evidence, never model output,
raw protocol records, session IDs, paths, credentials or existing conversation content.
"""
import argparse
import json
import os
import selectors
import subprocess
import tempfile
import time
import uuid


MESSAGE = "Fictional Seshlog transport check; reply OK. Do not access files or run tools."
MAX_RECORD = 1024 * 1024


class Probe:
    def __init__(self, executable, directory):
        # Existing authentication/permissions remain the agent's responsibility. No configuration
        # overrides, auto-approval responses or direct model-server requests are made by this script.
        self.process = subprocess.Popen(
            [executable, "app-server", "--stdio"], cwd=directory,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)
        self.buffer = b""
        self.events = []
        self.dispatched = False

    def write(self, value):
        encoded = json.dumps(value, ensure_ascii=False).encode("utf-8") + b"\n"
        if len(encoded) > MAX_RECORD:
            raise RuntimeError("request_limit")
        self.process.stdin.write(encoded)
        self.process.stdin.flush()

    def read(self, deadline):
        while b"\n" not in self.buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise RuntimeError("deadline")
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                raise RuntimeError("disconnected")
            self.buffer += chunk
            if len(self.buffer) > MAX_RECORD:
                raise RuntimeError("record_limit")
        line, self.buffer = self.buffer.split(b"\n", 1)
        value = json.loads(line)
        if not isinstance(value, dict):
            raise RuntimeError("invalid_record")
        return value

    def request(self, method, params, request_id, deadline):
        self.write({"id": request_id, "method": method, "params": params})
        for _ in range(2000):
            value = self.read(deadline)
            if value.get("id") == request_id and "method" not in value:
                return value
            self.observe(value)
        raise RuntimeError("event_limit")

    def observe(self, value):
        # Never answer permission, elicitation or tool requests on the user's behalf.
        if "method" in value and "id" in value:
            raise RuntimeError("agent_requires_user_input")
        if len(self.events) >= 2000:
            raise RuntimeError("event_limit")
        self.events.append(value)

    def close(self):
        self.selector.close()
        self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.process.stdout.close()


def run(executable):
    version = subprocess.run([executable, "--version"], capture_output=True, timeout=10, check=True)
    if version.stdout.decode("utf-8").strip() != "codex-cli 0.166.0":
        raise RuntimeError("unsupported_version")
    with tempfile.TemporaryDirectory(prefix="seshlog-diff-live-", dir="/private/tmp") as directory:
        probe = Probe(executable, directory)
        try:
            deadline = time.monotonic() + 90
            initialized = probe.request("initialize", {
                "clientInfo": {"name": "seshlog-d1-live-check", "version": "0.1.0"},
                "capabilities": {"experimentalApi": True},
            }, "initialize", deadline)
            if "error" in initialized:
                raise RuntimeError("initialize_refused")
            probe.write({"method": "initialized"})
            started = probe.request("thread/start", {"cwd": directory}, "start", deadline)
            thread = started.get("result", {}).get("thread", {}).get("id")
            if not isinstance(thread, str):
                raise RuntimeError("thread_start_refused")
            batch = str(uuid.uuid4())
            # After dispatch a timeout/error is unknown, never a reason to retry automatically.
            probe.dispatched = True
            response = probe.request("thread/queue/add", {
                "threadId": thread, "clientUserMessageId": batch,
                "input": [{"type": "text", "text": MESSAGE, "text_elements": []}],
            }, "queue", deadline)
            queued = response.get("result", {}).get("queuedSubmission", {})
            expected = [{"type": "text", "text": MESSAGE, "text_elements": []}]
            if not isinstance(queued.get("id"), str) or not queued["id"] or len(queued["id"]) > 256 or \
                    queued.get("clientUserMessageId") != batch or queued.get("input") != expected:
                raise RuntimeError("queue_receipt_unverified")
            print("SESHLOG_DIFF_LIVE version=0.166.0 queued_receipts=1", flush=True)
            for _ in range(2000):
                for event in probe.events:
                    if event.get("method") == "turn/completed" and event.get("params", {}).get("threadId") == thread:
                        status = event["params"].get("turn", {}).get("status")
                        if status != "completed":
                            raise RuntimeError("turn_not_completed")
                        print("SESHLOG_DIFF_LIVE completed_turns=1 real_model_turns=1", flush=True)
                        return
                probe.events.clear()
                probe.observe(probe.read(deadline))
            raise RuntimeError("event_limit")
        except Exception:
            print("SESHLOG_DIFF_LIVE outcome=" + ("unknown_after_dispatch" if probe.dispatched else "unavailable_before_dispatch"), flush=True)
            raise
        finally:
            # Closing a local connection does not recall a message already queued by the agent.
            probe.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", required=True, help="Codex 0.166.0 executable")
    args = parser.parse_args()
    if os.environ.get("SESHLOG_DIFF_REVIEW_LIVE") != "1":
        parser.error("requires explicit user authorization and SESHLOG_DIFF_REVIEW_LIVE=1")
    try:
        run(args.codex)
    except Exception:
        # Exception strings, server errors and model output can contain private information.
        print("SESHLOG_DIFF_LIVE check_failed=1", flush=True)
        raise SystemExit(1)
