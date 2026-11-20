"""chat43 (issue #477, voice typing in Russian): typed chat carries UTF-8. The REAL Chat.lua (with Safety.lua and TapPad.lua) under
tools/wowstub.py, through the real pad cells, as ChatStream.cellsOf spells a character of another script:
- each UTF-8 byte is a prefix cell (chcmd 2 = 0x80 + the next letter's code - 32, chcmd 3 = 0xC0 + it) then a letter cell (32..95);
- the check (15) answers the buffer's length and hash over its BYTES, and Send (23) makes ONE SendChatMessage of exactly that UTF-8;
- a delete (12) takes the whole last character (2 or 3 bytes), an ASCII one byte as before;
- a spacer (chs 36 / 39) between a prefix and its letter changes nothing; any typed pick drops a prefix still waiting;
- the prefix cells are refused while the chat does not hold the controller; ASCII typing is as before.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_chat_send as base      # noqa: E402  (its world, taps and checks; importing it runs its own checks once)
from wowstub import nsent          # noqa: E402

world, tap, last, sentchat, h = base.world, base.tap, base.last, base.sentchat, base.h

passed = 0


def ok(cond, what):
    global passed
    assert cond, what
    passed += 1


def cells(ch):
    """ChatStream.cellsOf: printable ASCII = its cell; else each UTF-8 byte = prefix then letter"""
    if ord(ch) < 128:
        return [base.cell(ch)]
    out = []
    for b in ch.encode("utf-8"):
        out.append(("chcmd", 2 if b < 0xC0 else 3))
        out.append(base.cell(chr((b & 0x3F) + 32)))
    return out


def type_text(L, s, spacer=False):
    for ch in s:
        for i, c in enumerate(cells(ch)):
            tap(L, *c)
            if spacer and c[0] == "chcmd":
                tap(L, "chs", 36)          # the stream's spacer between a prefix and its letter


def check(L):
    n0 = nsent(L)
    tap(L, "chq", 15)
    return last(L, "chatt", n0)


def blen(s):
    return len(s.encode("utf-8"))


def sent_text(L):
    s = sentchat(L)[-1]
    return s if isinstance(s, str) else s.decode("utf-8")


# ---------------------------------------------------------------- 1. Russian, typed byte by byte, checked and sent exactly
L = world()
msg = "Привет, мир"
type_text(L, msg)
a = check(L)
ok(a["ok"] is True and a["len"] == blen(msg) and a["h"] == h(msg), "the check reports the UTF-8 buffer: %r" % a)
tap(L, "chq", 23)
ok(sent_text(L) == "SAY|nil|" + msg, "one SAY send of the exact UTF-8: %r" % sentchat(L))

# ---------------------------------------------------------------- 2. delete takes one whole character
type_text(L, "да!")
tap(L, "chq", 12)                      # "!" (one byte)
tap(L, "chq", 12)                      # "а" (two bytes)
a = check(L)
ok(a["len"] == blen("д") and a["h"] == h("д"), "two deletes leave the first letter whole: %r" % a)
type_text(L, "日本")                    # three bytes each
tap(L, "chq", 12)
a = check(L)
ok(a["len"] == blen("д日") and a["h"] == h("д日"), "a delete takes all three bytes of a CJK character: %r" % a)
tap(L, "chq", 13)

# ---------------------------------------------------------------- 3. spacers between prefix and letter; a typed pick drops a prefix
type_text(L, "Ёжик", spacer=True)
a = check(L)
ok(a["len"] == blen("Ёжик") and a["h"] == h("Ёжик"), "a spacer between a prefix and its letter changes nothing: %r" % a)
tap(L, "chq", 13)
tap(L, "chcmd", 3)                     # a prefix ...
tap(L, "chq", 13)                      # ... then Clear: the prefix goes
type_text(L, "ok")
a = check(L)
ok(a["len"] == 2 and a["h"] == h("ok"), "a prefix does not outlive a typed pick: %r" % a)
tap(L, "chq", 13)

# ---------------------------------------------------------------- 4. mixed text and a whisper to a Cyrillic name
tap(L, "chq", 22)
tap(L, "chq", 14)
type_text(L, "Тралл")
tap(L, "chq", 15)
type_text(L, "go 2 Оргриммар?")
tap(L, "chq", 23)
ok(sent_text(L) == "WHISPER|Тралл|go 2 Оргриммар?", "the whisper went to the Cyrillic name: %r" % sentchat(L))

# ---------------------------------------------------------------- 5. not held: the prefix cells are refused too, nothing typed
L = world(holder="pad:quests")
n0 = nsent(L)
tap(L, "chcmd", 2)
a = last(L, "chatt", n0)
ok(a["ok"] is False and a["why"] == "the chat is not open", "not held: the prefix refused: %r" % a)

print("test_chat_utf8: %d checks passed" % passed)
