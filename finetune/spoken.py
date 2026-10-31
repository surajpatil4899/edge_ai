"""Parse times and durations as spoken / as Moonshine writes them.

The model copies these phrases verbatim (schema v2); this code turns them into numbers.
Kept in sync with android/.../SpokenTime.kt through spoken_cases.json (both test suites read it).
"""

import re

UNITS = {"zero": 0, "oh": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
         "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
         "seventeen": 17, "eighteen": 18, "nineteen": 19}
TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50}
PM_WORDS = ("pm", "afternoon", "evening", "tonight", "night")
AM_WORDS = ("am", "morning")


def _clean(text: str) -> str:
    t = text.lower().replace("-", " ")
    t = re.sub(r"\ba\.?\s*m\b\.?", " am ", t)
    t = re.sub(r"\bp\.?\s*m\b\.?", " pm ", t)
    t = re.sub(r"(\d)(am|pm)\b", r"\1 \2", t)
    t = re.sub(r"(\d)\.(\d\d)\b", r"\1:\2", t)  # "7.30"
    t = t.replace("o'clock", " ").replace("oclock", " ")
    t = re.sub(r"[^a-z0-9:. ]", " ", t)
    return re.sub(r"(?<!\d)\.|\.(?!\d)", " ", t)  # keep only decimal points ("1.5 hours")


def _words_to_numbers(t: str) -> list[str]:
    """'seven forty five' -> ['7', '45'];  'twenty one' -> ['21'];  digits pass through."""
    out, words = [], t.split()
    i = 0
    while i < len(words):
        w = words[i]
        if w in TENS:
            v = TENS[w]
            if i + 1 < len(words) and words[i + 1] in UNITS and 0 < UNITS[words[i + 1]] < 10:
                v += UNITS[words[i + 1]]
                i += 1
            out.append(str(v))
        elif w == "oh" and i + 1 < len(words) and words[i + 1] in UNITS and UNITS[words[i + 1]] < 10:
            out.append(f"0{UNITS[words[i + 1]]}")  # "seven oh five" -> 7 05
            i += 1
        elif w in UNITS:
            out.append(str(UNITS[w]))
        else:
            out.append(w)
        i += 1
    return out


def parse_time(text: str):
    """-> (hour 0-23, minute) or None. Without am/pm cues the hour is returned as said (1-12)."""
    t = _clean(text)
    if re.search(r"\bnoon\b|\bmidday\b", t):
        return (12, 0)
    if re.search(r"\bmidnight\b", t):
        return (0, 0)
    w = _words_to_numbers(t)
    s = " ".join(w)
    hour = minute = None
    m = re.search(r"\b(half|quarter|\d{1,2})\s+(?:minutes?\s+)?(past|after|to|before)\s+(\d{1,2})\b", s)
    if m:
        amount = {"half": 30, "quarter": 15}.get(m.group(1)) or int(m.group(1))
        h = int(m.group(3))
        if m.group(2) in ("past", "after"):
            hour, minute = h, amount
        else:
            hour, minute = (h - 1) % 24 or (12 if h <= 12 else 0), 60 - amount
    else:
        m = (re.search(r"\b(\d{1,2}):(\d{2})\b", s) or re.search(r"\b(\d{1,2})\s+(\d{1,2})\b", s)
             or re.search(r"\b(\d{1,2})(\d{2})\b", s) or re.search(r"\b(\d{1,2})\b()", s))
        if not m:
            return None
        hour, minute = int(m.group(1)), int(m.group(2) or 0)
    if hour > 23 or minute > 59:
        return None
    words = set(s.split())
    if words & set(PM_WORDS) and hour < 12:
        hour += 12
    elif words & set(AM_WORDS) and hour == 12:
        hour = 0
    if "night" in words and hour == 24:
        hour = 0
    return (hour, minute)


def parse_duration(text: str):
    """-> total seconds or None."""
    t = _clean(text)
    t = re.sub(r"\ban? hour and a half\b", "90 minutes", t)
    t = re.sub(r"\bhalf an? hour\b", "30 minutes", t)
    t = re.sub(r"\bhalf a minute\b", "30 seconds", t)
    t = re.sub(r"\b(an?|one)\b(?=\s+(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b)", "1", t)
    s = " ".join(_words_to_numbers(t))
    s = re.sub(r"(\d+) and a half (hours?|hrs?|minutes?|mins?)", r"\1.5 \2", s)
    total, found = 0.0, False
    for num, unit in re.findall(r"(\d+(?:\.\d+)?)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b", s):
        found = True
        total += float(num) * (3600 if unit.startswith("h") else 60 if unit.startswith("m") else 1)
    return int(round(total)) if found else None
