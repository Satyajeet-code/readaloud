"""Desktop tests for the Python backend (no Android needed).

    python tools/test_backend.py [path/to/kokoro-int8-multi-lang-v1_0.tar.bz2]
"""
import json
import os
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))
import readaloud_backend as rb  # noqa: E402

failures = 0


def check(name, got, want):
    global failures
    ok = got == want
    failures += not ok
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"\n   got:  {got!r}\n   want: {want!r}"))


# --- link detection -------------------------------------------------------
check("bare url", rb.looks_like_link("https://example.com/a?b=1"), "https://example.com/a?b=1")
check("www url", rb.looks_like_link("  www.example.com/news.  "), "https://www.example.com/news")
check("shared title + url", rb.looks_like_link("Great read: Why cities matter https://t.co/abc"), "https://t.co/abc")
check("prose with url is text", rb.looks_like_link("x " * 150 + "see https://a.com"), "")
check("plain text", rb.looks_like_link("Hello there, read this."), "")
check("two urls is text", rb.looks_like_link("https://a.com and https://b.com"), "")

# --- normalisation --------------------------------------------------------
n = rb.normalize_for_speech
check("dollars", n("It cost $1,250 today."), "It cost 1,250 dollars today.")
check("dollar singular", n("Only $1 left"), "Only 1 dollar left")
check("millions", n("raised $3.5 million"), "raised 3.5 million dollars")
check("rupees", n("Price Rs. 500 and ₹20 crore"), "Price 500 rupees and 20 crore rupees")
check("percent", n("up 20% on year"), "up 20 percent on year")
check("abbrev", n("Dr. Rao met Mr. Shah, e.g. at 5 pm"), "Doctor Rao met Mister Shah, for example at 5 pm")
check("month", n("on Nov 7, 2026"), "on November 7, 2026")
check("acronym", n("the U.S.A. and U.K. deal"), "the USA and UK deal")
check("url removed", n("Read https://x.com/y now"), "Read now")
check("dash pause", n("It works — mostly"), "It works, mostly")

# --- chunking -------------------------------------------------------------
text = ("Dr. Smith arrived at 10 a.m. on Monday. The meeting ran long! Everyone agreed? "
        + "Yes. " * 3 + "\n\nSecond paragraph starts here. It has two sentences.")
chunks = rb.chunk_text(text, max_chars=120, first_chars=60)
check("first chunk short", len(chunks[0]) <= 60, True)
check("no chunk too long", all(len(c) <= 120 for c in chunks), True)
check("paragraph boundary", any(c.startswith("Second paragraph") for c in chunks), True)
check("abbrev not split", chunks[0].startswith("Doctor Smith arrived"), True)
long_sentence = "word " * 200
check("long sentence split", all(len(c) <= 280 for c in rb.chunk_text(long_sentence)), True)
hindi = "भारत एक विशाल देश है। यहाँ कई भाषाएँ बोली जाती हैं। हर राज्य की अपनी संस्कृति है।"
p = json.loads(rb.prepare(hindi, 40))
check("hindi detected", p["lang"], "hi")
check("hindi split on danda", len(p["chunks"]) >= 2, True)
check("english detected", json.loads(rb.prepare("Hello world."))["lang"], "en")

# --- extraction -----------------------------------------------------------
html = """<html><head><title>Site | Big News</title>
<meta property="og:title" content="Big News Today"></head><body>
<header class="site-header"><nav><a href="/">Home</a><a href="/w">World</a></nav></header>
<div class="share-bar">Share on Twitter Share on Facebook</div>
<article class="post-content"><h1>Big News Today</h1>
<p>The council approved the budget on Tuesday, after a long debate about transport, housing, and schools.</p>
<div class="ad-slot">Advertisement</div>
<p>Officials said the plan would cut commute times by twenty percent, though critics disagree strongly.</p>
<h2>What happens next</h2>
<p>Work begins in March, with the first new routes expected to open in the autumn of next year.</p>
</article>
<aside class="related"><p>Related: Ten things you missed this week in the city and around the region.</p></aside>
<footer><p>Copyright 2026 Example News. All rights reserved by the company and partners.</p></footer>
</body></html>"""
title, body = rb.extract_article(html.encode())
check("title", title, "Big News Today")
check("body starts", body.split("\n\n")[0][:39], "The council approved the budget on Tues")
check("heading kept as sentence", "What happens next." in body, True)
check("ads/related/footer dropped",
      any(s in body for s in ("Advertisement", "Related:", "Copyright", "Share on")), False)

ld = """<html><head><script type="application/ld+json">{"@type":"NewsArticle","headline":"LD title",
"articleBody":"%s"}</script></head><body><div><p>Teaser only.</p></div></body></html>""" % ("Body sentence. " * 60)
check("json-ld body used", rb.extract_article(ld)[1].startswith("Body sentence."), True)

md = "Title: X\n\n# Heading\n\nSome **bold** text with a [link](http://a.b).\n\n- item one\n![img](x.png)"
check("markdown to text", rb.markdown_to_text(md).split(), "Title: X Heading. Some bold text with a link. item one".split())

# --- model unpacking ------------------------------------------------------
if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
    class Sink:
        def __init__(self): self.seen = set()
        def update(self, stage, pct): self.seen.add((stage, pct // 25))
        def isCancelled(self): return False

    with tempfile.TemporaryDirectory() as d:
        sink = Sink()
        t = time.time()
        keep = ["model.int8.onnx", "voices.bin", "tokens.txt", "LICENSE"]
        got = rb.unpack(sys.argv[1], d, keep, sink)
        check("unpack kept files", sorted(got), sorted(keep))
        check("unpack only kept", sorted(os.listdir(d)), sorted(keep))
        check("unpack progress reported", ("unpack", 4) in sink.seen or ("unpack", 3) in sink.seen, True)
        print(f"     unpacked in {time.time() - t:.1f}s")

    class CancelSink(Sink):
        def isCancelled(self): return True

    with tempfile.TemporaryDirectory() as d:
        import shutil
        arc = os.path.join(d, "a.tar.bz2")
        shutil.copy(sys.argv[1], arc)
        res = json.loads(rb.install_model("http://unused", arc, os.path.join(d, "m"),
                                          json.dumps(["model.int8.onnx"]), CancelSink()))
        check("cancel honoured", res, {"ok": False, "error": "cancelled"})

print("\nALL PASSED" if not failures else f"\n{failures} FAILED")
sys.exit(1 if failures else 0)
