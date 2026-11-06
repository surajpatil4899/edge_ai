"""Generate the command -> function-call training set (schema v3, finetune/functions.json).

  generate_data.py [--n 9000] [--noise 0.4] [--seed 0]  -> data/train.jsonl, data/val.jsonl

Each example is {"messages": [developer, user, assistant], "meta": {...}}; with tools from
functions.json it is what `tokenizer.apply_chat_template(messages, tools=tools)` expects for FunctionGemma. The assistant turn
is one or more tool calls (several actions in one sentence), or a short reply (no call) for non-commands.
"Open X and do Y" inside an app becomes the one call that opens the app at that spot (play_media, navigate, ...).

Slot values are rendered the many ways people say them and Moonshine writes them; transcription
noise is applied to the template words only, so the labels (copied spans) stay exact.
Examples whose text appears in a held-out set (data/eval_*.jsonl) are dropped.
"""

import argparse
import json
import os
import random
import re

from evaluate import parse_when
from spoken import parse_duration

HERE = os.path.dirname(os.path.abspath(__file__))
SYSTEM = "You are a model that can do function calling with the following functions"
NO_CALL_REPLY = "Sorry, I can't help with that."
R = random.Random(0)

# ---------------------------------------------------------------- slot values
NUM_WORDS = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"]
MIN_WORDS = {5: "oh five", 10: "ten", 15: "fifteen", 20: "twenty", 25: "twenty five", 30: "thirty", 35: "thirty five",
             40: "forty", 45: "forty five", 50: "fifty", 55: "fifty five"}
WEEKDAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def spoken_time():
    """A time phrase as said/transcribed, e.g. '6 am', '5:45 p.m.', 'seven thirty', 'half past 6'."""
    h = R.randint(1, 12)
    m = R.choice([0, 0, 0, 30, 30, 15, 45, 10, 20, 5, 40, 50])
    mer = R.choice(["am", "pm", "a.m.", "p.m.", "a.m", "AM", "PM", "", "", "in the morning", "in the evening", "tonight"])
    if mer == "tonight" and h < 6:
        mer = "pm"
    words = R.random() < 0.3
    hs = NUM_WORDS[h] if words else str(h)
    if m == 0:
        base = R.choice([hs, f"{hs} o'clock"]) if mer in ("", "in the morning", "in the evening", "tonight") else hs
    elif m in (15, 30, 45) and R.random() < 0.25:
        base = {15: f"quarter past {hs}", 30: f"half past {hs}", 45: f"quarter to {NUM_WORDS[h % 12 + 1] if words else h % 12 + 1}"}[m]
    elif words:
        base = f"{hs} {MIN_WORDS.get(m, str(m))}"
    else:
        base = f"{h}:{m:02d}"
    if mer and R.random() < 0.15 and mer in ("am", "pm") and base[-1].isdigit():
        return base + mer  # "6am" (digits only: "thirtypm" would not parse)
    return f"{base} {mer}".strip()


def spoken_delay():
    """A delay for an alarm or reminder ("6 hours", "twenty minutes", "an hour and a half"); no seconds."""
    kind = R.random()
    if kind < 0.45:
        n = R.choice([1, 2, 3, 4, 5, 6, 7, 8, 2, 6])
        return f"{R.choice([str(n), NUM_WORDS[n]])} {'hour' if n == 1 else R.choice(['hours', 'hours', 'hrs'])}"
    if kind < 0.8:
        n = R.choice([5, 10, 15, 20, 30, 40, 45, 90])
        return f"{R.choice([str(n), NUM_WORDS[n]]) if n <= 12 else n} {R.choice(['minutes', 'minutes', 'mins'])}"
    return R.choice(["half an hour", "an hour", "an hour and a half", "two and a half hours", "1 hour 30 minutes"])


def spoken_duration():
    kind = R.random()
    if kind < 0.45:
        n = R.choice([1, 2, 3, 5, 5, 10, 10, 15, 20, 25, 30, 40, 45, 90])
        unit = "minute" if n == 1 else R.choice(["minutes", "minutes", "mins", "minute"])
        return f"{R.choice([str(n), NUM_WORDS[n]]) if n <= 12 else n} {unit}"
    if kind < 0.6:
        n = R.choice([10, 15, 20, 30, 45, 60, 90])
        return f"{n} {R.choice(['seconds', 'secs', 'second'])}"
    if kind < 0.75:
        h, m = R.choice([1, 1, 2]), R.choice([10, 15, 20, 30, 45])
        return R.choice([f"{h} hour {m} minutes", f"{h} hours and {m} minutes", f"{h} hour and {m} minutes"]).replace("1 hours", "1 hour")
    if kind < 0.9:
        return R.choice(["half an hour", "an hour", "an hour and a half", "half a minute", "a minute", "two hours", "one hour"])
    return f"{R.choice(['2', 'two', '3', 'three'])} and a half minutes"


CONTACTS = ["mom", "mummy", "papa", "dad", "maa", "didi", "bhaiya", "my wife", "my husband", "my brother", "my sister", "grandma",
            "Rahul", "Priya", "Sanket", "Amit", "Neha", "Rohan", "Pooja", "Vikram", "Anjali", "Arjun", "Sneha", "Karan", "Divya",
            "Suresh", "Kavita", "Aditya", "Meera", "Siddharth", "Riya", "Nikhil", "Shreya", "Manoj", "Asha", "John", "Sarah",
            "Mike", "Emma", "David", "Lisa", "Tom", "Alex", "the office", "Doctor Mehta", "Ravi uncle", "Sunita aunty",
            "Pranav", "Ishaan", "Tanvi", "Omkar", "Sachin", "Ganesh", "Lakshmi", "Faizan", "Zoya", "Harpreet", "Joseph"]
NUMBERS = ["9876543210", "98765 43210", "9123456780", "100", "112", "1800 180 1234"]
MESSAGES = ["I will be late", "I reached home", "good morning", "call me when you are free", "I am on my way", "running late by ten minutes",
            "happy birthday", "where are you", "I will call you back", "dinner is ready", "see you tomorrow", "can you pick me up",
            "the meeting is postponed", "I am stuck in traffic", "thank you so much", "please send the documents", "I am outside",
            "don't forget the milk", "let's meet at the station", "I forgot my keys", "the class is cancelled", "all the best",
            "I am in a meeting", "lunch at one", "book the tickets", "get well soon", "good night", "on the way home", "ok done",
            "reach by seven", "bring an umbrella", "I paid the bill"]
TASKS = ["buy milk", "call the bank", "take my medicine", "pay the electricity bill", "go to the hospital", "submit the report",
         "water the plants", "pick up the kids", "book the train tickets", "check my email", "go to the gym", "call mom",
         "drink water", "charge my phone", "renew the insurance", "buy vegetables", "attend the meeting", "send the invoice",
         "feed the dog", "visit grandma", "pay the rent", "collect the parcel", "recharge my phone", "go for a walk",
         "prepare the presentation", "wish Rahul happy birthday", "take out the trash", "buy groceries", "visit the dentist",
         "finish the assignment", "order the cake", "transfer money to dad", "check the gas", "lock the door"]
EVENTS = ["a doctor's appointment", "doctors appointment", "a dentist appointment", "a meeting with Rahul", "a meeting",
          "an exam", "an interview", "a flight", "a call with the team", "a parent teacher meeting", "a haircut appointment",
          "a party", "a presentation", "a train to catch", "a client call", "my driving test", "a yoga class", "a wedding",
          "an eye checkup", "the gym", "my sister's birthday party", "a bank appointment", "a video call with mom", "an online class"]
LABELS = ["medicine", "gym", "work", "the meeting", "yoga", "school", "my flight", "prayer", "the train", "office"]
APPS = ["WhatsApp", "YouTube", "Instagram", "Gmail", "Google Maps", "maps", "Spotify", "settings", "calculator",
        "Paytm", "PhonePe", "Google Pay", "the gallery", "photos", "Chrome", "the browser", "Facebook", "Twitter", "Netflix",
        "Amazon", "Flipkart", "Swiggy", "Zomato", "Uber", "Ola", "the clock", "calendar", "contacts", "the dialer", "messages",
        "Telegram", "LinkedIn", "Snapchat", "the music app", "files", "notes", "Hotstar", "Gaana", "the weather app",
        "what's up", "whats app", "you tube", "insta", "G pay", "play store", "the play store"]
MEDIA = ["Arijit Singh", "bollywood songs", "lofi music", "Shape of You", "Kesariya", "old Hindi songs", "Believer",
         "workout music", "Diljit Dosanjh", "AP Dhillon", "funny cat videos", "cricket highlights", "Taylor Swift",
         "relaxing music", "Ed Sheeran", "Kishore Kumar songs", "Marathi songs", "devotional songs", "Hanuman Chalisa",
         "Tum Hi Ho", "Coldplay", "how to make pasta", "Python tutorial", "rain sounds", "90s hits", "Punjabi songs",
         "Lata Mangeshkar", "Despacito", "IPL highlights", "Naatu Naatu", "a bollywood song", "romantic songs",
         "the news", "Sid Sriram", "Alan Walker", "Shreya Ghoshal", "kids songs", "Ganpati songs", "party songs", "jazz"]
MEDIA_APPS = ["YouTube", "YouTube", "YouTube", "youtube", "you tube", "Spotify", "Spotify", "spotify", "YouTube Music",
              "YT Music", "JioSaavn", "Gaana", "Wynk", "Amazon Music"]
QUERIES = ["the weather in Pune", "weather today", "cricket score", "how tall is Mount Everest", "petrol price today",
           "IPL schedule", "news today", "meaning of serendipity", "how to make tea", "Deccan Queen train status",
           "Virat Kohli", "gold rate today", "who won the match yesterday", "Diwali date", "dollar to rupee rate",
           "Python list comprehension", "symptoms of dengue", "time in London", "best phones under 20000",
           "how to reset my router", "Pune to Mumbai distance", "recipe for paneer butter masala", "holidays in December"]
PLACES = ["the airport", "Pune station", "home", "office", "Shivaji Nagar", "the nearest hospital", "Phoenix Mall",
          "Hinjewadi", "Mumbai", "the railway station", "Koregaon Park", "my office", "Lonavala", "the bus stand",
          "Swargate", "Dagdusheth temple", "the Gateway of India", "Bangalore airport", "Andheri station", "Baner",
          "Rahul's house", "the hospital", "college", "the stadium"]
NEARBY = ["petrol pumps", "a petrol pump", "restaurants", "ATMs", "an ATM", "hospitals", "coffee shops", "medical stores",
          "a pharmacy", "hotels", "parking", "EV charging stations", "chinese restaurants", "gyms", "bus stops",
          "pizza places", "a mechanic", "grocery stores"]
CHAT_APPS = ["WhatsApp", "WhatsApp", "WhatsApp", "whatsapp", "what's app", "Telegram", "Signal"]
STORE_APPS = ["Swiggy", "Zomato", "a PDF scanner", "PhonePe", "Instagram", "a calculator app", "Candy Crush", "Ola",
              "Truecaller", "a VPN", "Duolingo", "a photo editor", "Free Fire", "Hotstar", "WhatsApp Business",
              "a QR code scanner", "Uber", "Google Pay", "a music player", "Netflix"]

# ---------------------------------------------------------------- templates per function
# {time} {day} {label} {duration} {task} {contact} {message} {app} are slots; others are literals.
TEMPLATES = {
    "set_alarm": [
        "set an alarm for {time}", "set alarm for {time}", "wake me up at {time}", "alarm at {time}", "set an alarm at {time}",
        "can you set an alarm for {time}", "please wake me up at {time}", "{time} alarm", "set my alarm for {time}",
        "set an alarm for {time} {day}", "set an alarm for {day} at {time}", "wake me up {day} at {time}",
        "set an alarm for {time} for {label}", "alarm for {label} at {time}", "set a {label} alarm for {time}",
        "I need to wake up at {time}", "put an alarm for {time}", "create an alarm for {time} {day}",
        # delays: the time is copied as the delay ("6 hours"); the app adds it to now
        "set an alarm after {delay}", "set an alarm in {delay}", "wake me up in {delay}", "wake me up after {delay}",
        "alarm after {delay}", "set alarm for {delay} from now", "ring an alarm in {delay}", "set alarm after {delay}",
    ],
    "set_timer": [
        "set a timer for {duration}", "start a timer for {duration}", "timer for {duration}", "set timer {duration}",
        "countdown {duration}", "start a {duration} timer", "set a {duration} timer", "can you start a timer for {duration}",
        "put a timer for {duration}", "time {duration} for me",
    ],
    "create_reminder": [
        "remind me to {task}", "remind me to {task} at {time}", "remind me to {task} {day}", "remind me {day} to {task}",
        "remind me to {task} {day} at {time}", "remind me at {time} to {task}", "set a reminder to {task}",
        "set a reminder to {task} at {time}", "don't let me forget to {task}", "reminder to {task} {day} at {time}",
        "remind me that I have to {task} {day}", "please remind me to {task} at {time} {day}", "create a reminder to {task}",
        "remind me that I have {event} {day} at {time}", "remind me that I have {event} {day} {time}",
        "remind me I have {event} at {time}", "remind me about {event} {day}", "remind me about {event} at {time}",
        "I have {event} {day} at {time} remind me", "remind me that I have {event} at {time} {day}",
        "remind me that there is {event} {day}",
        "remind me to {task} in {delay}", "remind me in {delay} to {task}", "remind me after {delay} to {task}",
        "in {delay} remind me to {task}",
    ],
    "call_contact": [
        "call {contact}", "phone {contact}", "give {contact} a call", "dial {contact}", "ring {contact}", "make a call to {contact}",
        "call {contact} please", "can you call {contact}", "place a call to {contact}", "call to {contact}",
    ],
    "send_message": [
        "send a message to {contact} saying {message}", "text {contact} that {message}", "message {contact} {message}",
        "tell {contact} that {message}", "send {contact} a text saying {message}", "send a text to {contact} {message}",
        "send message to {contact} {message}", "text {contact} {message}", "write to {contact} saying {message}",
        "send an SMS to {contact} saying {message}",
    ],
    "play_media": [
        "play {query} on {mapp}", "play {query} in {mapp}", "open {mapp} and play {query}", "open {mapp} and search for {query}",
        "search {query} on {mapp}", "put on {query} on {mapp}", "{mapp} play {query}", "can you play {query} on {mapp}",
        "I want to listen to {query} on {mapp}", "watch {query} on {mapp}", "open {mapp} and play some {query}",
        "play {query}", "play some {query}", "I want to listen to {query}", "put on {query}", "can you play {query}",
        "play me {query}", "start playing {query}",
    ],
    "web_search": [
        "search for {q}", "google {q}", "search google for {q}", "look up {q}", "search the web for {q}",
        "open chrome and search for {q}", "open google and search {q}", "find {q} on google", "can you search {q}",
        "search {q}", "do a google search for {q}", "search online for {q}",
    ],
    "navigate": [
        "navigate to {dest}", "take me to {dest}", "directions to {dest}", "how do I get to {dest}", "start navigation to {dest}",
        "open maps and navigate to {dest}", "show me the way to {dest}", "get directions to {dest}", "drive to {dest}",
        "open google maps and take me to {dest}", "route to {dest}", "navigate me to {dest}",
    ],
    "search_maps": [
        "find {near} near me", "show {near} nearby", "open maps and find {near}", "search maps for {near}",
        "show me {near} on the map", "find {near} on google maps", "{near} near me", "look for {near} nearby",
        "open google maps and search {near}", "are there any {near} nearby",
    ],
    "message_app": [
        "open {capp} and message {contact} {message}", "send a {capp} message to {contact} saying {message}",
        "{capp} {contact} {message}", "message {contact} on {capp} that {message}", "send {contact} a {capp} saying {message}",
        "text {contact} on {capp} {message}", "on {capp} tell {contact} that {message}", "open {capp} and send {contact} {message}",
        "send {message} to {contact} on {capp}", "open {capp} chat with {contact}", "open {capp} and message {contact}",
    ],
    "search_app_store": [
        "search the play store for {store}", "find {store} on the play store", "open play store and search {store}",
        "download {store}", "install {store}", "look for {store} in the play store", "get {store} from the play store",
        "open the play store and find {store}", "search {store} in play store",
    ],
    "dismiss_alarm_at": [
        "stop the {time} alarm", "cancel my {time} alarm", "turn off the alarm for {time}", "delete the {time} alarm",
        "stop the alarm of {time}", "cancel the alarm at {time}", "remove my alarm for {time}", "switch off the {time} alarm",
    ],
}
FIXED = {  # (phrase, args)
    "set_flashlight": [(p, {"state": "on"}) for p in ["turn on the flashlight", "flashlight on", "torch on", "turn on the torch",
                       "switch on the flashlight", "switch on torch", "turn the flashlight on", "light on the torch", "enable flashlight"]]
                      + [(p, {"state": "off"}) for p in ["turn off the flashlight", "flashlight off", "torch off", "turn off the torch",
                         "switch off the flashlight", "switch off torch", "turn the torch off", "disable flashlight"]],
    "set_volume": [(p, {"action": "up"}) for p in ["volume up", "increase the volume", "turn the volume up", "louder", "raise the volume"]]
                  + [(p, {"action": "down"}) for p in ["volume down", "decrease the volume", "turn the volume down", "lower the volume", "reduce volume"]]
                  + [(p, {"action": "mute"}) for p in ["mute the phone", "mute", "silent mode", "put the phone on silent", "mute my phone"]]
                  + [(p, {"action": "unmute"}) for p in ["unmute", "unmute the phone", "turn off silent mode", "turn the sound back on"]]
                  + [(p, {"action": "vibrate"}) for p in ["put my phone on vibrate", "vibrate mode", "switch to vibrate", "set phone to vibrate"]]
                  + [(p, {"action": "max"}) for p in ["full volume", "maximum volume", "volume to max", "turn the volume all the way up"]],
    "set_do_not_disturb": [(p, {"state": "on"}) for p in ["turn on do not disturb", "enable DND", "do not disturb on", "turn on DND",
                           "switch on do not disturb", "don't disturb me", "enable do not disturb mode"]]
                          + [(p, {"state": "off"}) for p in ["turn off do not disturb", "disable DND", "do not disturb off",
                             "turn off DND", "switch off do not disturb", "disable do not disturb"]],
    "media_control": [(p, {"action": "play"}) for p in ["play music", "play the song", "resume the music", "resume", "start the music", "play"]]
                     + [(p, {"action": "pause"}) for p in ["pause the music", "pause", "pause the song", "hold the music", "pause playback"]]
                     + [(p, {"action": "next"}) for p in ["next song", "skip this song", "play the next song", "next track", "skip"]]
                     + [(p, {"action": "previous"}) for p in ["previous song", "go back to the last song", "play the previous track", "last song"]]
                     + [(p, {"action": "stop"}) for p in ["stop the music", "stop playing", "stop the song"]],
    "dismiss_alarm": [(p, {"action": "dismiss"}) for p in ["stop the alarm", "turn off the alarm", "dismiss the alarm", "cancel the alarm",
                       "alarm off", "shut the alarm"]]
                     + [(p, {"action": "snooze"}) for p in ["snooze", "snooze the alarm", "snooze for a bit", "five more minutes"]],
    "camera": [(p, {"mode": "photo"}) for p in ["open the camera", "open camera", "take a photo", "take a picture", "click a photo",
               "open the camera in photo mode", "launch camera", "start the camera", "click a picture"]]
              + [(p, {"mode": "selfie"}) for p in ["take a selfie", "open the front camera", "open the camera in selfie mode",
                 "selfie mode", "switch to the selfie camera", "click a selfie", "open selfie camera"]]
              + [(p, {"mode": "video"}) for p in ["record a video", "open the camera in video mode", "start recording a video",
                 "video mode", "shoot a video", "open the video camera", "take a video"]],
}
OPEN_APP = ["open {app}", "launch {app}", "start {app}", "open the {app} app", "can you open {app}", "go to {app}", "show me {app}"]

NAME_CHAT = ["what are you doing {n}", "thanks {n}", "thank you {n}", "hey {n} how are you", "{n} come here",
             "good morning {n}", "where is {n}", "I am going out with {n}", "{n} is my friend", "bye {n}", "hi {n}",
             "{n} said he will come", "did {n} reach home", "{n} is calling me", "I talked to {n} yesterday"]
TIME_CHAT = ["I woke up at {t} today", "the meeting was at {t}", "I slept at {t} last night", "the shop opens at {t}",
             "my train leaves at {t}", "it is already {t}", "I usually wake up at {t}", "the match starts at {t}",
             "yesterday I reached office at {t}", "is the bank open at {t}", "what happens at {t}",
             "I have a meeting tomorrow", "tomorrow is my birthday", "today is monday", "I was busy on friday"]

NON_COMMANDS = [
    "what are you doing", "how are you", "thank you", "thanks", "okay", "ok", "hmm", "yes", "no", "hello", "hi there",
    "what is the weather today", "will it rain tomorrow", "tell me a joke", "who is the prime minister of India",
    "what is your name", "who made you", "what can you do", "what else can you do", "I am bored", "good morning",
    "what's the score", "how far is Mumbai", "what is two plus two", "translate hello to Hindi", "play cricket with me",
    "the traffic was terrible this morning", "did you watch the match last night", "I was thinking about dinner",
    "my phone is very slow today", "I bought a new computer", "she is my personal assistant", "let's go home now",
    "I am very happy to be able to speak to you today", "I don't know what to do", "it's really hard to believe it",
    "the first thing that I want to say is this", "In the 19th century", "I'm going to tell you a story",
    "it's", "is", "and", "the", "uh", "it", "a", "it's okay", "is it", "the edge", "hey", "okay edge",
    "what time is it", "where is my phone", "how old are you", "sing a song", "I love you", "nothing", "never mind",
    "cancel", "wait", "sorry", "what did you say", "can you hear me", "repeat that", "let me think",
    # capability questions: the app answers these itself; the model must not act on them
    "what are the tasks you can do", "what all can you do", "tell me what you can do", "what are your features",
    "how can you help me", "what commands do you know", "give me examples of what you can do", "what can I ask you",
    "what do you do", "what are the things you can do locally", "help", "what are the tasks you can",
    # fragments and background speech (field logs)
    "the internet is not a phone app but it is a big deal", "that's not plastic", "do it work", "what is this",
    "if you don't have a car you can't do it", "push it", "conde", "corned head", "ah", "yeah yeah",
]
DIGITS = ["100000000000000000", "1 2 3 4", "2026", "45", "9 9 9", "0000000000"]

# ---------------------------------------------------------------- transcription noise
WAKE_RESIDUE = ["Is ", "It's ", "Okay edge ", "Okay, ", "A. ", "He ", "Edge, ", "And ", "Okay edge, ", "Ok ", "Is, ",
                "Which ", "Which, ", "Wich ", "What ", "We ", "Age, ", "Okay age "]
CONFUSIONS = [  # (pattern on template words, replacements) - observed in Moonshine output from the phone
    (r"\bset an alarm\b", ["set in alarm", "said an alarm", "set a alarm", "set alarm", "and alarm"]),
    (r"\bset alarm\b", ["said alarm", "set a alarm", "set in alarm"]),
    (r"\bremind me\b", ["remind be", "reminded me", "remind me", "remember me"]),
    (r"\bset a reminder\b", ["set the reminder", "said a reminder", "set reminder"]),
    (r"^call\b", ["calm", "cold", "cool", "col"]),
    (r"\bturn on\b", ["turn and", "turned on", "turn own"]),
    (r"\bturn off\b", ["turn of", "turned off"]),
    (r"\bpause\b", ["pass", "paws", "pose"]),
    (r"\btimer\b", ["time", "tamer"]),
    (r"\bmessage\b", ["massage", "message"]),
    (r"\bflashlight\b", ["flash light", "flashlight"]),
    (r"\btext\b", ["takes", "text"]),
    (r"\bvolume\b", ["volume", "valium", "wear him", "value", "volume"]),
    (r"\bwake me up\b", ["wakniak up", "make me up", "wake me a pet", "make me a pet", "wake meet up", "weak me up",
                          "wake me", "wakey up", "we can be up", "wake up"]),
    (r"\bremind me\b", ["remind me", "find me", "remain me"]),
]

GARBLE_SWAPS = [("w", "v"), ("v", "w"), ("e", "a"), ("a", "e"), ("o", "a"), ("th", "d"), ("ck", "k"), ("sh", "s"),
                ("m", "n"), ("n", "m"), ("p", "b"), ("t", "d"), ("i", "ee"), ("u", "a")]


def garble_word(w: str) -> str:
    """Letter-level corruption like a misheard word: substitute a sound, drop or merge letters."""
    op = R.random()
    if op < 0.5:
        a, b = R.choice([s for s in GARBLE_SWAPS if s[0] in w] or [("", "")])
        return w.replace(a, b, 1) if a else w
    if op < 0.75 and len(w) > 4:
        i = R.randrange(1, len(w) - 1)
        return w[:i] + w[i + 1:]
    return w + R.choice(["s", "e", "y", "ed"])


def noisy(template_text: str) -> str:
    """Noise on the literal words only (never inside {slot} placeholders), so labels stay exact."""
    parts = re.split(r"(\{\w+\})", template_text)
    lit = " \x00 ".join(parts[0::2])  # literals joined with markers; placeholders sit at the markers
    for pat, reps in CONFUSIONS:
        if re.search(pat, lit) and R.random() < 0.35:
            lit = re.sub(pat, R.choice(reps), lit, count=1)
    if R.random() < 0.08:  # drop a function word
        lit = re.sub(r"\b(the|a|an|for|to|me)\b ", "", lit, count=1)
    if R.random() < 0.3:  # mishear one command word; slots stay intact so the model learns to lean on them
        words = [w for w in re.findall(r"[a-z]{4,}", lit)]
        if words:
            w = R.choice(words)
            lit = re.sub(rf"\b{w}\b", garble_word(w), lit, count=1)
    literals = lit.split(" \x00 ")
    out = []
    for i, literal in enumerate(literals):
        out.append(literal)
        if 2 * i + 1 < len(parts):
            out.append(parts[2 * i + 1])
    return "".join(out)


def surface(text: str, asr_style: bool) -> str:
    """Casing / punctuation like Moonshine ("Set a timer for 10 minutes.") or plain."""
    text = re.sub(r"\s+", " ", text).strip()
    if asr_style or R.random() < 0.5:
        text = text[0].upper() + text[1:]
    if R.random() < 0.35:
        text += R.choice([".", ".", ",", "?"])
    return text


# ---------------------------------------------------------------- example builders
def day_slot():
    d = R.choice(["tomorrow", "tomorrow", "today", "tonight"] + WEEKDAYS)
    if d == "tonight":
        return "tonight", "today"
    if d == "tomorrow" and R.random() < 0.3:
        return R.choice(["to-morrow", "to morrow", "tomorow", "tommorow", "tomorrow morning"]), "tomorrow"
    if d in WEEKDAYS:
        return R.choice([f"on {d}", f"this {d}", d, f"next {d}"]).replace(d, d.capitalize() if R.random() < 0.5 else d), d
    return d, d


def build(name: str, noise: bool):
    if name in TEMPLATES:
        tpl = R.choice(TEMPLATES[name])
        tpl = noisy(tpl) if noise else tpl
        args, fills = {}, {}
        for slot in re.findall(r"\{(\w+)\}", tpl):
            if slot == "time":
                v = spoken_time()
                fills["time"], args["time"] = v, v
            elif slot == "day":
                said, value = day_slot()
                fills["day"], args["day"] = said, value
            elif slot == "duration":
                v = spoken_duration()
                fills["duration"], args["duration"] = v, v
            elif slot == "label":
                v = R.choice(LABELS)
                fills["label"], args["label"] = v, v
            elif slot == "task":
                v = R.choice(TASKS)
                fills["task"], args["text"] = v, v
            elif slot == "event":
                v = R.choice(EVENTS)
                fills["event"], args["text"] = v, re.sub(r"^(a|an|my|the) ", "", v)
            elif slot == "contact":
                v = R.choice(CONTACTS + (NUMBERS if name == "call_contact" else []))
                fills["contact"], args["contact"] = v, v
            elif slot == "message":
                v = R.choice(MESSAGES)
                fills["message"], args["message"] = v, v
            elif slot == "delay":
                v = spoken_delay()
                fills["delay"], args["time"] = v, v
            elif slot == "query":
                v = R.choice(MEDIA)
                fills["query"], args["query"] = v, v
            elif slot == "mapp":
                v = R.choice(MEDIA_APPS)
                fills["mapp"], args["app"] = v, v
            elif slot == "q":
                v = R.choice(QUERIES)
                fills["q"], args["query"] = v, v
            elif slot == "dest":
                v = R.choice(PLACES)
                fills["dest"], args["destination"] = v, v
            elif slot == "near":
                v = R.choice(NEARBY)
                fills["near"], args["query"] = v, re.sub(r"^(a|an) ", "", v)
            elif slot == "capp":
                v = R.choice(CHAT_APPS)
                fills["capp"], args["app"] = v, v
            elif slot == "store":
                v = R.choice(STORE_APPS)
                fills["store"], args["query"] = v, v
        text = tpl.format(**fills)
        if name == "dismiss_alarm_at":
            name, args = "dismiss_alarm", {"action": "dismiss", **args}
        # "tonight" sets the meridiem: keep it with the copied time so "8:30" stays 20:30.
        if args.get("day") == "today" and "tonight" in text and "time" in args and "tonight" not in args["time"]:
            args["time"] += " tonight"
        if "time" in args and re.search(r"\b(morning|evening|afternoon)\b", text) and not re.search(
                r"\b(morning|evening|afternoon|tonight|am|pm|a\.m|p\.m)\b", args["time"], re.I):
            args["time"] += " " + re.search(r"\b(morning|evening|afternoon)\b", text).group(1)
        return text, {"name": name, "arguments": args}
    if name == "open_app":
        tpl = R.choice(OPEN_APP)
        app = R.choice(APPS)
        text = tpl.format(app=app)
        return text, {"name": name, "arguments": {"app": app.removeprefix("the ")}}
    phrase, args = R.choice(FIXED[name])
    return (noisy(phrase) if noise else phrase), {"name": name, "arguments": dict(args)}


def example(text: str, calls: list, meta):
    msgs = [{"role": "developer", "content": SYSTEM}, {"role": "user", "content": text}]
    if calls:
        msgs.append({"role": "assistant", "tool_calls": [{"type": "function", "function": c} for c in calls]})
    else:
        msgs.append({"role": "assistant", "content": NO_CALL_REPLY})
    return {"messages": msgs, "meta": meta}


WEIGHTS = {"set_alarm": 14, "create_reminder": 14, "set_timer": 9, "call_contact": 10, "send_message": 10, "set_flashlight": 4,
           "set_volume": 5, "set_do_not_disturb": 4, "media_control": 6, "open_app": 6, "dismiss_alarm": 3,
           "dismiss_alarm_at": 3, "play_media": 10, "web_search": 6, "navigate": 6, "search_maps": 5, "message_app": 8,
           "camera": 4, "search_app_store": 4}
# Several actions in one sentence; joined parts keep their own wording ("turn on DND and set an alarm for 6").
MULTI = ["set_alarm", "set_timer", "create_reminder", "call_contact", "send_message", "set_flashlight", "set_volume",
         "set_do_not_disturb", "media_control", "open_app", "play_media", "navigate", "camera"]
JOINERS = [" and ", " and ", " and then ", ", then ", ". Also ", " and also ", ", and ", " then "]


def valid(call) -> bool:
    a = call["arguments"]
    if "time" in a and parse_when(a["time"]) is None:
        return False
    if "duration" in a and parse_duration(a["duration"]) is None:
        return False
    return True


def multi(noise: bool):
    """Two (sometimes three) different actions in one sentence, calls in spoken order."""
    k = 3 if R.random() < 0.15 else 2
    names = R.sample(MULTI, k)
    parts, calls = [], []
    for name in names:
        text, call = build(name, noise)
        if not valid(call):
            return None
        parts.append(text)
        calls.append(call)
    text = parts[0]
    for part in parts[1:]:
        # "Also turn on DND", but "and WhatsApp mom" keeps its capital.
        text += R.choice(JOINERS) + (part[0].lower() + part[1:] if part[1:2].islower() else part)
    return text, calls


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--n", type=int, default=9000, help="total examples (train + val)")
    p.add_argument("--noise", type=float, default=0.4, help="fraction with transcription noise")
    p.add_argument("--no-call", type=float, default=0.15, help="fraction of non-commands")
    p.add_argument("--multi", type=float, default=0.12, help="fraction of commands with several actions")
    p.add_argument("--val", type=float, default=0.1)
    p.add_argument("--seed", type=int, default=0)
    a = p.parse_args()
    R.seed(a.seed)

    eval_texts = {json.loads(l)["text"].lower().strip(" .,?") for name in ("eval_seed.jsonl", "eval_phone.jsonl", "eval_v3.jsonl")
                  for l in open(os.path.join(HERE, "data", name))}
    names, weights = list(WEIGHTS), list(WEIGHTS.values())
    seen, rows = {}, []
    while len(rows) < a.n:
        noise = R.random() < a.noise
        if R.random() < a.no_call:
            kind = R.random()
            if kind < 0.22:
                text = R.choice(NAME_CHAT).format(n=R.choice([c for c in CONTACTS if not c.startswith(("my ", "the "))]))
            elif kind < 0.4:
                text = R.choice(TIME_CHAT).format(t=spoken_time())
            elif kind < 0.43:
                text = R.choice(DIGITS)
            elif kind < 0.55:  # garbled speech: misheard words must not turn into an action
                words = R.choice(NON_COMMANDS).split()
                for _ in range(R.randint(1, 2)):
                    i = R.randrange(len(words))
                    words[i] = garble_word(words[i])
                text = " ".join(words)
            else:
                text = R.choice(NON_COMMANDS)
            calls = []
            if noise and R.random() < 0.5:
                text = R.choice(WAKE_RESIDUE) + text
        elif R.random() < a.multi:
            built = multi(noise)
            if built is None:
                continue
            text, calls = built
            if noise and R.random() < 0.35:
                text = R.choice(WAKE_RESIDUE) + text
        else:
            text, call = build(R.choices(names, weights)[0], noise)
            if not valid(call):
                continue
            calls = [call]
            if noise and R.random() < 0.35:
                text = R.choice(WAKE_RESIDUE) + text
        text = surface(text, asr_style=noise)
        key = text.lower().strip(" .,?")
        # Fixed-phrase functions have few distinct phrasings; allow a few repeats so they aren't starved.
        limit = 3 if len(calls) == 1 and calls[0]["name"] in FIXED else 1
        if key in eval_texts or (calls and seen.get(key, 0) >= limit):
            continue
        seen[key] = seen.get(key, 0) + 1
        function = None if not calls else calls[0]["name"] if len(calls) == 1 else "multi"
        rows.append(example(text, calls, {"noise": noise, "function": function}))

    R.shuffle(rows)
    n_val = int(len(rows) * a.val)
    os.makedirs(os.path.join(HERE, "data"), exist_ok=True)
    for name, part in (("val", rows[:n_val]), ("train", rows[n_val:])):
        with open(os.path.join(HERE, "data", f"{name}.jsonl"), "w") as f:
            for r in part:  # tools come from functions.json at training time
                f.write(json.dumps(r) + "\n")
        print(f"{name}: {len(part)} examples")
    from collections import Counter
    print(Counter(r["meta"]["function"] or "<no call>" for r in rows).most_common())
    print(f"noisy: {sum(r['meta']['noise'] for r in rows)} / {len(rows)}")


if __name__ == "__main__":
    main()
