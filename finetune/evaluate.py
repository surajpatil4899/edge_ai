"""Score a FunctionGemma model on a labelled command set via an OpenAI-compatible llama-server.

  evaluate.py [--url http://127.0.0.1:8080] [--data data/eval_seed.jsonl] [--out results.jsonl]

Sends each transcript exactly as the app does (developer turn + tools from functions.json, stop at
<start_function_response>, temperature 0), parses the FunctionGemma calls and reports:
  - function accuracy: right functions in the right order, or correctly no call for non-commands
  - exact accuracy:    right functions and every argument right
A row's "expect" is one call {name, args}, a list of calls (several actions in one sentence), or null.
broken down by source (clean / asr_real / asr_synthetic / none), plus the errors.
"""

import argparse
import collections
import difflib
import json
import os
import re
import urllib.request

from spoken import parse_duration, parse_time

HERE = os.path.dirname(os.path.abspath(__file__))
SYSTEM = "You are a model that can do function calling with the following functions"
FREE_TEXT = {"text", "message", "label", "query", "destination"}
# App names are copied as heard ("what's up", "you tube"); the app matches them to installed apps by sound.
FUZZY = {"app", "contact"}
DEFAULTS = {}
ESC = "<escape>"
END_CALL = "<end_function_call>"
STOP = "<start_function_response>"  # follows the last call; see the chat template

# "6 hours", "in 20 minutes", "after an hour and a half" are delays, not clock times (twin of TimeResolver.relative).
RELATIVE = re.compile(r"^(?:in|after)?\s*(.+?(?:hours?|hrs?|minutes?|mins?|seconds?|secs?|and a half))\s*(?:from now|later)?$", re.I)
CLOCK_WORDS = re.compile(r"\b(past|to|before|after)\b", re.I)


def parse_when(text: str):
    """("in", seconds) for a delay, ("at", (hour, minute)) for a clock time, or None."""
    m = RELATIVE.match(text.strip())
    if m and not CLOCK_WORDS.search(m.group(1)):
        seconds = parse_duration(m.group(1))
        if seconds:
            return "in", seconds
    t = parse_time(text)
    return ("at", t) if t is not None else None


# Spoken-value fields are compared by meaning: "6 am" == "6 a.m" == "six am", "6 hours" == "in six hours".
BY_MEANING = {"time": parse_when, "duration": parse_duration}


def parse_calls(content: str) -> list[dict]:
    """All calls in a reply, in order (several actions in one sentence). Twin of parseCalls() in LlmChat.kt."""
    return [c for c in (parse_call(chunk) for chunk in content.split(END_CALL)) if c]


def parse_call(content: str):
    """The first call in [content]. Python twin of parseCall() in LlmChat.kt."""
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


def fuzzy_match(expected, got) -> bool:
    e, g = re.sub(r"[^a-z0-9]", "", str(expected).lower()), re.sub(r"[^a-z0-9]", "", str(got).lower())
    return bool(e) and difflib.SequenceMatcher(None, e, g).ratio() >= 0.7


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
        elif k in FUZZY:
            if not fuzzy_match(v, got[k]):
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
        "tools": tools, "temperature": 0, "max_tokens": 200, "cache_prompt": True,
        "stop": [STOP, "<end_of_turn>"],
    }
    req = urllib.request.Request(f"{url}/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["choices"][0]["message"].get("content") or ""


def score(row: dict, raw: str) -> dict:
    """Score one model reply against a labelled row (shared with the Colab notebook)."""
    got, want = parse_calls(raw), row["expect"]
    wants = [] if want is None else (want if isinstance(want, list) else [want])
    names = [c["name"] for c in got]
    if not wants:
        fn_ok = not got
        errs = [] if fn_ok else [f"called {'+'.join(names)} on a non-command"]
    else:
        fn_ok = names == [w["name"] for w in wants]
        errs = ([e for w, g in zip(wants, got) for e in arg_errors(w["args"], g["args"])] if fn_ok
                else [f"called {'+'.join(names) or 'nothing'} (want {'+'.join(w['name'] for w in wants)})"])
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
