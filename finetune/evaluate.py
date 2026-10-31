"""Score a FunctionGemma model on a labelled command set via an OpenAI-compatible llama-server.

  evaluate.py [--url http://127.0.0.1:8080] [--data data/eval_seed.jsonl] [--out results.jsonl]

Sends each transcript exactly as the app does (developer turn + tools from functions.json, stop at
<end_function_call>, temperature 0), parses the FunctionGemma call and reports:
  - function accuracy: right function, or correctly no call for non-commands
  - exact accuracy:    right function and every argument right
broken down by source (clean / asr_real / asr_synthetic / none), plus the errors.
"""

import argparse
import collections
import json
import os
import re
import urllib.request

from spoken import parse_duration, parse_time

HERE = os.path.dirname(os.path.abspath(__file__))
SYSTEM = "You are a model that can do function calling with the following functions"
FREE_TEXT = {"text", "message", "label"}
DEFAULTS = {}
# Spoken-value fields are compared by meaning: "6 am" == "6 a.m" == "six am".
BY_MEANING = {"time": parse_time, "duration": parse_duration}
ESC = "<escape>"


def parse_call(content: str):
    """Python twin of parseCall() in LlmChat.kt."""
    m = re.search(r"call:([A-Za-z_][A-Za-z0-9_]*)\{(.*)", content, re.S)
    if not m:
        return None
    body, args, i = m.group(2).rstrip("}"), {}, 0
    while i < len(body):
        colon = body.find(":", i)
        if colon < 0:
            break
        key = body[i:colon].strip().lstrip(",{").strip()
        j = colon + 1
        if body.startswith(ESC, j):
            end = body.find(ESC, j + len(ESC))
            end = len(body) if end < 0 else end
            value, j = body[j + len(ESC) : end], end + len(ESC)
        else:
            end = body.find(",", j)
            end = len(body) if end < 0 else end
            value, j = body[j:end].strip(), end
        if key and key not in args:  # keep the first value if the model repeats a key
            args[key] = value
        i = j + 1 if j < len(body) and body[j] == "," else j
    return {"name": m.group(1), "args": args}


def norm(value) -> str:
    return re.sub(r"[^a-z0-9' ]", "", str(value).lower()).strip()


def text_match(expected, got) -> bool:
    e, g = set(norm(expected).split()), set(norm(got).split())
    return bool(e) and len(e & g) / len(e) >= 0.6


def arg_errors(expect: dict, got: dict) -> list[str]:
    errors = []
    for k, v in expect.items():
        if k not in got:
            errors.append(f"missing {k}={v}")
        elif k in BY_MEANING:
            want_v, got_v = BY_MEANING[k](str(v)), BY_MEANING[k](str(got[k]))
            if got_v is None or got_v != want_v:
                errors.append(f"{k}={got[k]!r} (want {v!r})")
        elif k in FREE_TEXT:
            if not text_match(v, got[k]):
                errors.append(f"{k}={got[k]!r} (want {v!r})")
        elif norm(got[k]) != norm(v):
            errors.append(f"{k}={got[k]} (want {v})")
    for k, v in got.items():
        if k not in expect and not (k in DEFAULTS and norm(v) == norm(DEFAULTS[k])):
            errors.append(f"extra {k}={v}")
    return errors


def ask(url: str, tools, text: str) -> str:
    body = {
        "messages": [{"role": "developer", "content": SYSTEM}, {"role": "user", "content": text}],
        "tools": tools, "temperature": 0, "max_tokens": 96, "cache_prompt": True,
        "stop": ["<end_function_call>"],
    }
    req = urllib.request.Request(f"{url}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["choices"][0]["message"].get("content") or ""


def score(row: dict, raw: str) -> dict:
    """Score one model reply against a labelled row (shared with the Colab notebook)."""
    got, want = parse_call(raw), row["expect"]
    if want is None:
        fn_ok = got is None
        errs = [] if fn_ok else [f"called {got['name']} on a non-command"]
    else:
        fn_ok = got is not None and got["name"] == want["name"]
        errs = (arg_errors(want["args"], got["args"]) if fn_ok
                else [f"called {got['name'] if got else 'nothing'} (want {want['name']})"])
    return {**row, "raw": raw, "got": got, "function_ok": fn_ok, "exact": fn_ok and not errs, "errors": errs}


def summarize(results: list[dict]) -> str:
    by_source = collections.defaultdict(lambda: [0, 0, 0])
    for r in results:
        s = by_source[r["source"]]
        s[0], s[1], s[2] = s[0] + 1, s[1] + r["function_ok"], s[2] + r["exact"]
    total = [sum(v[i] for v in by_source.values()) for i in range(3)]
    lines = ["== accuracy"]
    for src, (n, f, e) in sorted(by_source.items()) + [("ALL", total)]:
        lines.append(f"  {src:14} n={n:3}  function {100 * f / n:5.1f}%   exact {100 * e / n:5.1f}%")
    err_types = collections.Counter(re.sub(r"[=(].*", "", e).strip() for r in results for e in r["errors"])
    lines.append("== most common errors: " + ", ".join(f"{k} ×{v}" for k, v in err_types.most_common(10)))
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:8080")
    p.add_argument("--data", default=os.path.join(HERE, "data", "eval_seed.jsonl"))
    p.add_argument("--out", help="write per-example results as JSONL")
    a = p.parse_args()
    tools = json.load(open(os.path.join(HERE, "functions.json")))
    rows = [json.loads(line) for line in open(a.data) if line.strip()]

    results = []
    for r in rows:
        res = score(r, ask(a.url, tools, r["text"]))
        results.append(res)
        mark = "✓" if res["exact"] else ("~" if res["function_ok"] else "✗")
        print(f"{mark} [{r['source']:13}] {r['text']!r:58.58} {'; '.join(res['errors'])}", flush=True)
    print()
    print(summarize(results))
    if a.out:
        with open(a.out, "w") as f:
            for res in results:
                f.write(json.dumps(res) + "\n")


if __name__ == "__main__":
    main()
