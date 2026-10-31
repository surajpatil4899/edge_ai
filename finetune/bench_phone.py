"""Latency + behaviour check of a GGUF model served by llama-server on the phone.

  adb forward tcp:8080 tcp:8080   (bench_phone.sh does this and starts the server)
  bench_phone.py [--url http://127.0.0.1:8080] [--runs 2]

Sends voice-command transcripts (clean and real ASR errors) with the function schema in
functions.json and reports llama-server's own timings: prefill (prompt incl. tool declarations)
and decode, cold (first request) vs warm (prompt prefix cached), plus the parsed function call.
"""

import argparse
import json
import os
import statistics
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SYSTEM = "You are a model that can do function calling with the following functions"

COMMANDS = [
    "Set an alarm for 6 am tomorrow",
    "Set a timer for 10 minutes",
    "Remind me to buy milk at 5 pm",
    "Send a message to Rahul saying I will be late",
    "Turn on the flashlight",
    "Pause the music",
    # real Moonshine transcripts from the phone recordings
    "Set a alarm for 2am",
    "Saturn reminded that I want to go to hospital tomorrow",
    "Calm mom",
    "Turn on alarm 460M",
    # not a command
    "What are you doing",
]


def ask(url, tools, text, max_tokens):
    body = {
        "messages": [{"role": "developer", "content": SYSTEM}, {"role": "user", "content": text}],
        "tools": tools,
        "temperature": 0,
        "max_tokens": max_tokens,
        "cache_prompt": True,
        # FunctionGemma keeps going after a call ("<start_function_response>..."); the app stops here.
        "stop": ["<end_function_call>"],
    }
    req = urllib.request.Request(f"{url}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=300) as r:
        out = json.load(r)
    wall = (time.perf_counter() - t) * 1000
    msg = out["choices"][0]["message"]
    calls = [f"{c['function']['name']}({c['function']['arguments']})" for c in msg.get("tool_calls") or []]
    return out.get("timings", {}), wall, calls or [repr((msg.get("content") or "").strip()[:120])]


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:8080")
    p.add_argument("--runs", type=int, default=2)
    p.add_argument("--max-tokens", type=int, default=64)
    a = p.parse_args()
    tools = json.load(open(os.path.join(HERE, "functions.json")))

    rows = []
    for run in range(a.runs):
        for text in COMMANDS:
            tm, wall, calls = ask(a.url, tools, text, a.max_tokens)
            rows.append((run, text, tm, wall, calls))
            print(f"[run {run}] {wall:6.0f} ms  prefill {tm.get('prompt_n', '?'):>4} tok {tm.get('prompt_ms', 0):6.0f} ms  "
                  f"decode {tm.get('predicted_n', '?'):>3} tok {tm.get('predicted_ms', 0):6.0f} ms  | {text!r:50} -> {'; '.join(calls)}",
                  flush=True)

    first = rows[0]
    warm = [r for r in rows[1:]]
    print("\n== summary")
    print(f"cold first request: {first[3]:.0f} ms wall, prefill {first[2].get('prompt_n')} tokens at "
          f"{first[2].get('prompt_per_second', 0):.0f} tok/s")
    if warm:
        print(f"warm requests:      median {statistics.median(r[3] for r in warm):.0f} ms wall, "
              f"max {max(r[3] for r in warm):.0f} ms; median prefill {statistics.median(r[2].get('prompt_n', 0) for r in warm):.0f} tokens; "
              f"decode {statistics.median(r[2].get('predicted_per_second', 0) for r in warm):.0f} tok/s")


if __name__ == "__main__":
    main()
