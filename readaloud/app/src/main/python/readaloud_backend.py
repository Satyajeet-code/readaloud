"""
ReadAloud Python backend.

Runs inside the Android app through Chaquopy (it is plain CPython, so it also
runs on a desktop for testing). The Kotlin side calls these functions:

    fetch(url)                     -> JSON: article title + text from a link
    looks_like_link(text)          -> the URL if the input is "just a link", else ""
    prepare(text, max_chars)       -> JSON: language + normalised speech chunks
    install_model(url, archive, dest_dir, keep_json, sink)
                                   -> JSON: download + unpack a TTS model

Only pure-Python dependencies are used (requests, beautifulsoup4, pypdf) so the
build never depends on native wheels being available for Android.
"""

from __future__ import annotations

import html as html_lib
import io
import json
import os
import re
import tarfile
import unicodedata
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup, Comment, NavigableString, Tag

# ---------------------------------------------------------------------------
# Link detection
# ---------------------------------------------------------------------------

URL_RE = re.compile(r"(?:https?://|www\.)[^\s<>\"'`]+", re.IGNORECASE)
_TRAILING = ".,;:!?)]}>'\"”’"


def _clean_url(u: str) -> str:
    u = u.strip().rstrip(_TRAILING)
    if u.lower().startswith("www."):
        u = "https://" + u
    return u


def looks_like_link(text: str) -> str:
    """Return the URL when the input is a link (or a shared "Title - link"
    snippet), otherwise "" so the caller treats it as text to read."""
    if not text:
        return ""
    s = text.strip()
    m = URL_RE.search(s)
    if not m:
        return ""
    rest = (s[: m.start()] + s[m.end():]).strip()
    # Browsers share "Article title https://..." -- still a link.
    # Real prose that happens to contain a URL is longer than that.
    if len(rest) <= 200 and len(URL_RE.findall(s)) == 1:
        return _clean_url(m.group(0))
    return ""


# ---------------------------------------------------------------------------
# Fetching
# ---------------------------------------------------------------------------

UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,application/pdf;q=0.8,*/*;q=0.7",
    "Accept-Language": "en-IN,en;q=0.9,hi;q=0.8",
}
READER_ENDPOINT = "https://r.jina.ai/"
MIN_GOOD_CHARS = 500
MAX_DOWNLOAD = 15 * 1024 * 1024


def _result(ok: bool, **kw) -> str:
    kw["ok"] = ok
    return json.dumps(kw, ensure_ascii=False)


def _get(url: str, timeout=(10, 30), headers=None) -> requests.Response:
    r = requests.get(url, headers=headers or HEADERS, timeout=timeout,
                     allow_redirects=True, stream=True)
    chunks, size = [], 0
    try:
        for chunk in r.iter_content(64 * 1024):
            chunks.append(chunk)
            size += len(chunk)
            if size > MAX_DOWNLOAD:
                break
    finally:
        r.close()
    r._content = b"".join(chunks)  # noqa: SLF001 - cap the body size
    return r


def fetch(url: str) -> str:
    """Fetch a link and return JSON {ok, title, text, url, source, error}."""
    url = _clean_url(url)
    if not urlparse(url).scheme:
        url = "https://" + url
    title, text, source, final_url, err = "", "", "direct", url, ""

    try:
        r = _get(url)
        final_url = r.url or url
        ctype = r.headers.get("Content-Type", "").lower()
        if r.status_code >= 400:
            err = f"The site answered HTTP {r.status_code}."
        elif "pdf" in ctype or final_url.lower().split("?")[0].endswith(".pdf") \
                or r.content[:5] == b"%PDF-":
            title, text = _pdf_text(r.content)
            source = "pdf"
        elif ctype.startswith("text/plain"):
            text = r.text
        else:
            raw = r.content
            enc = r.encoding if "charset" in ctype else None
            title, text = extract_article(raw, final_url, encoding=enc)
    except requests.RequestException as e:
        err = f"Could not reach the site ({e.__class__.__name__})."
    except Exception as e:  # pragma: no cover - defensive, reported to the UI
        err = f"Could not read the page: {e}"

    # Script-heavy pages, paywalls and bot walls often give little text when
    # fetched directly; a reader service renders them and returns plain text.
    if len(text) < MIN_GOOD_CHARS and source != "pdf":
        r_title, r_text = _reader_fallback(url)
        if len(r_text) > len(text):
            title, text, source = r_title or title, r_text, "reader"

    text = tidy_text(text)
    if not text:
        return _result(False, error=err or "No readable text found on that page.", url=final_url)
    return _result(True, title=tidy_text(title), text=text, url=final_url,
                   source=source, chars=len(text))


def _reader_fallback(url: str):
    try:
        r = _get(READER_ENDPOINT + url, timeout=(10, 45),
                 headers={"User-Agent": UA, "Accept": "text/plain", "X-Return-Format": "text"})
        if r.status_code >= 400:
            return "", ""
        body = r.text
    except Exception:
        return "", ""
    title = ""
    m = re.search(r"^Title:\s*(.+)$", body, re.M)
    if m:
        title = m.group(1).strip()
    m = re.search(r"^(?:Markdown Content|Content):\s*$", body, re.M)
    if m:
        body = body[m.end():]
    return title, markdown_to_text(body)


def _pdf_text(data: bytes):
    from pypdf import PdfReader  # imported lazily: only needed for PDFs

    reader = PdfReader(io.BytesIO(data))
    title = ""
    try:
        if reader.metadata and reader.metadata.title:
            title = str(reader.metadata.title)
    except Exception:
        pass
    pages = []
    for page in reader.pages[:300]:
        try:
            pages.append(page.extract_text() or "")
        except Exception:
            continue
    text = "\n\n".join(pages)
    # Re-join words hyphenated across line breaks and lines inside paragraphs.
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", text)
    text = re.sub(r"(?<![.!?:\n])\n(?!\n)", " ", text)
    return title, text


# ---------------------------------------------------------------------------
# Article extraction (readability-style, pure Python)
# ---------------------------------------------------------------------------

# Always removed: never carry article prose.
DEAD_TAGS = ["script", "style", "noscript", "iframe", "svg", "canvas", "button", "input",
             "select", "textarea", "template", "video", "audio", "picture", "source",
             "object", "embed"]
# Usually chrome, but some sites wrap the whole page in one of these (e.g. an
# ASP.NET <form>), so they are only removed when they hold little prose.
CHROME_TAGS = ["form", "nav", "aside", "footer", "dialog", "menu", "header"]
BLOCK_TAGS = {"p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "dd", "dt",
              "figcaption", "td", "th", "div", "section", "article", "main", "ul", "ol",
              "table", "header", "footer", "aside", "nav", "form", "figure", "dl"}
# Matched against class/id/role *words* ("related-posts" -> related, posts),
# so "pageRow" or "non-ad-column" are not mistaken for "pager" or "ad".
NEGATIVE_WORDS = {
    "comment", "comments", "share", "sharing", "social", "related", "recommended",
    "recommendations", "promo", "promotion", "advert", "advertisement", "sponsor", "sponsored",
    "sidebar", "newsletter", "subscribe", "subscription", "signup", "cookie", "cookies",
    "consent", "banner", "popup", "modal", "footer", "masthead", "breadcrumb", "breadcrumbs",
    "pagination", "pager", "tags", "taglist", "widget", "toolbar", "outbrain", "taboola",
    "disqus", "trending", "paywall", "gdpr", "print", "caption", "credit", "credits", "skip",
    "byline", "author", "bio", "nav", "navbar", "menu", "header", "masthead", "mostpopular",
    "readmore", "alsoread", "newsletters", "partner", "partners", "recirc", "recirculation",
}
NEGATIVE_LEADING = {"ad", "ads", "adsbygoogle", "advert", "dfp", "gpt"}  # only as the first word
NEGATIVE_PHRASES = re.compile(r"most-?popular|read-?more|also-?read|more-?stories|you-?may-?like", re.I)
POSITIVE_WORDS = {"article", "body", "content", "entry", "main", "post", "story", "text",
                  "blog", "prose", "news", "articlebody", "storybody", "post-content"}


def _words(attr: str) -> list[list[str]]:
    out = []
    for token in attr.split():
        parts = [p for p in re.split(r"[-_:.]|(?<=[a-z])(?=[A-Z])", token) if p]
        out.append([p.lower() for p in parts])
    return out


def _is_negative(attr: str) -> bool:
    if NEGATIVE_PHRASES.search(attr):
        return True
    for parts in _words(attr):
        if parts and parts[0] in NEGATIVE_LEADING:
            return True
        if any(p in NEGATIVE_WORDS for p in parts):
            return True
    return False


def _is_positive(attr: str) -> bool:
    return any(p in POSITIVE_WORDS for parts in _words(attr) for p in parts)
BOILERPLATE_LINE = re.compile(
    r"^(advertisement|advertisements|sponsored|read more|read also|also read|related:?|"
    r"share( this)?( article| story)?|subscribe( now)?|sign up|follow us.*|click here.*|"
    r"continue reading|skip to (main )?content|loading\.\.\.|image( source)?:.*|"
    r"photo:.*|getty images|reuters|file photo|watch:.*|listen to (this|the) article.*)$",
    re.I,
)


def _attr_text(tag: Tag) -> str:
    if not isinstance(tag, Tag) or tag.attrs is None:
        return ""
    cls = tag.get("class") or []
    if isinstance(cls, str):
        cls = [cls]
    return " ".join(cls) + " " + (tag.get("id") or "") + " " + (tag.get("role") or "")


def _text_of(tag: Tag) -> str:
    return re.sub(r"\s+", " ", tag.get_text(" ", strip=True)).strip()


def _link_density(tag: Tag, text_len: int) -> float:
    if text_len == 0:
        return 1.0
    link_len = sum(len(a.get_text(" ", strip=True)) for a in tag.find_all("a"))
    return link_len / text_len


def _json_ld_article(soup: BeautifulSoup):
    best_title, best_body = "", ""
    for s in soup.find_all("script", attrs={"type": re.compile("ld\\+json", re.I)}):
        raw = s.string or s.get_text() or ""
        try:
            # strict=False: many sites leave raw newlines inside JSON strings
            data = json.loads(raw.strip().rstrip(";"), strict=False)
        except Exception:
            continue
        stack = [data]
        while stack:
            node = stack.pop()
            if isinstance(node, list):
                stack.extend(node)
            elif isinstance(node, dict):
                body = node.get("articleBody") or node.get("text")
                if isinstance(body, str) and len(body) > len(best_body):
                    best_body = body
                    t = node.get("headline") or node.get("name") or ""
                    best_title = t if isinstance(t, str) else ""
                for v in node.values():
                    if isinstance(v, (list, dict)):
                        stack.append(v)
    best_body = html_lib.unescape(best_body)
    if "<" in best_body and ">" in best_body:
        best_body = BeautifulSoup(best_body, "html.parser").get_text("\n")
    return best_title, best_body


def _title(soup: BeautifulSoup) -> str:
    for attrs in ({"property": "og:title"}, {"name": "twitter:title"}):
        m = soup.find("meta", attrs=attrs)
        if m and m.get("content"):
            return m["content"].strip()
    h1 = soup.find("h1")
    if h1 and _text_of(h1):
        return _text_of(h1)
    if soup.title and soup.title.string:
        return soup.title.string.strip()
    return ""


def _prose_len(tag: Tag) -> int:
    """Characters of paragraph-like text inside a tag."""
    n = len(_text_of(tag)) if tag.name == "p" else 0
    return n + sum(len(p.get_text(" ", strip=True)) for p in tag.find_all("p"))


def _divs_to_paragraphs(root: Tag) -> None:
    """Like readability: a <div> with no block-level children is a paragraph.
    Sites that write text as <div>…<br><br>…</div> then score correctly."""
    for br in root.find_all("br"):
        br.replace_with("\n")
    for d in root.find_all(["div", "span", "font", "section"]):
        if getattr(d, "_decomposed", False):
            continue
        if d.find(lambda t: isinstance(t, Tag) and t.name in BLOCK_TAGS):
            continue
        if len(d.get_text(" ", strip=True)) >= 60 and d.name != "span":
            d.name = "p"


def _remove_junk(root: Tag) -> None:
    for c in root.find_all(string=lambda s: isinstance(s, Comment)):
        c.extract()
    for name in DEAD_TAGS:
        for t in root.find_all(name):
            t.decompose()
    for t in root.find_all(attrs={"aria-hidden": "true"}):
        t.decompose()
    for t in root.find_all(attrs={"hidden": True}):
        t.decompose()
    _divs_to_paragraphs(root)

    total = max(1, _prose_len(root))
    for t in list(root.find_all(True)):
        if getattr(t, "_decomposed", False):  # removed with an ancestor
            continue
        if t.name in ("html", "body", "article", "main", "p"):
            continue
        a = _attr_text(t)
        chrome_tag = t.name in CHROME_TAGS
        negative = bool(a.strip()) and _is_negative(a) and not _is_positive(a)
        if not (chrome_tag or negative):
            continue
        if chrome_tag and _is_positive(a):
            continue
        # Safety net: never drop a block holding a big share of the prose.
        if _prose_len(t) > 0.3 * total:
            continue
        t.decompose()


def _score_candidates(root: Tag):
    scores: dict[int, float] = {}
    nodes: dict[int, Tag] = {}
    for p in root.find_all(["p", "pre", "blockquote", "li", "td"]):
        text = _text_of(p)
        if len(text) < 25:
            continue
        if p.name == "li" and len(text) < 80:
            continue
        score = 1 + text.count(",") + text.count("،") + text.count("।") + min(len(text) / 100, 3)
        parent = p.parent
        level = 0
        while isinstance(parent, Tag) and level < 3:
            key = id(parent)
            nodes[key] = parent
            scores[key] = scores.get(key, 0.0) + score / (1, 2, 3)[level]
            parent = parent.parent
            level += 1
    best, best_score = None, 0.0
    for key, s in scores.items():
        tag = nodes[key]
        a = _attr_text(tag)
        if _is_positive(a):
            s *= 1.25
        if tag.name in ("article", "main"):
            s *= 1.3
        tl = len(_text_of(tag))
        s *= 1 - min(_link_density(tag, tl), 0.9)
        if s > best_score:
            best, best_score = tag, s
    return best


def _blocks_text(container: Tag) -> list[str]:
    """Collect readable blocks in document order."""
    out: list[str] = []
    seen: set[str] = set()

    def emit(t: str, heading: bool = False):
        t = re.sub(r"\s+", " ", t).strip()
        if not t or BOILERPLATE_LINE.match(t):
            return
        if t in seen and len(t) < 200:
            return
        seen.add(t)
        if heading and t[-1:] not in ".!?:।":
            t += "."
        out.append(t)

    def walk(node: Tag):
        for child in node.children:
            if isinstance(child, NavigableString):
                continue
            if not isinstance(child, Tag):
                continue
            name = child.name
            if name in ("h1", "h2", "h3", "h4", "h5", "h6"):
                emit(_text_of(child), heading=True)
            elif name in ("p", "blockquote", "pre", "dd", "dt"):
                txt = _text_of(child)
                if len(txt) >= 2 and _link_density(child, len(txt)) < 0.6:
                    emit(txt)
            elif name == "li":
                txt = _text_of(child)
                if child.find(["p", "ul", "ol"]):
                    walk(child)
                elif len(txt) > 20 and _link_density(child, len(txt)) < 0.5:
                    emit(txt if txt[-1:] in ".!?;:" else txt + ".")
            elif name in ("table",):
                continue
            else:
                # div/section/span soup: descend, but also keep "bare" text
                # paragraphs that some CMSes put directly in divs.
                direct = "".join(
                    s for s in child.find_all(string=True, recursive=False)
                ).strip()
                if len(direct) > 80 and not child.find(["p", "div"]):
                    emit(_text_of(child))
                else:
                    walk(child)

    walk(container)
    return out


def extract_article(raw, url: str = "", encoding: str | None = None):
    """Return (title, text) for an HTML document (bytes or str)."""
    if isinstance(raw, bytes):
        soup = BeautifulSoup(raw, "html.parser", from_encoding=encoding)
    else:
        soup = BeautifulSoup(raw, "html.parser")
    title = _title(soup)
    ld_title, ld_body = _json_ld_article(soup)

    body = soup.body or soup
    _remove_junk(body)

    candidate = _score_candidates(body)
    blocks: list[str] = []
    if candidate is not None:
        # Readability trick: the best node is often one of several sibling
        # sections of the same article; widen to the parent when siblings
        # also carry paragraph text.
        parent = candidate.parent
        if isinstance(parent, Tag) and parent.name not in ("body", "html", "[document]"):
            cand_len = len(_text_of(candidate))
            sib_len = sum(
                len(_text_of(s)) for s in parent.find_all("p", recursive=True)
            )
            if sib_len > cand_len * 1.6 and _link_density(parent, len(_text_of(parent))) < 0.25:
                candidate = parent
        blocks = _blocks_text(candidate)

    text = "\n\n".join(blocks)
    if len(text) < 300:
        paras = [_text_of(p) for p in body.find_all("p")]
        alt = "\n\n".join(p for p in paras if len(p) > 40)
        if len(alt) > len(text):
            text = alt

    # Structured data written by the publisher is usually the cleanest copy.
    # Keep the DOM text only when it clearly contains that copy and more
    # (some sites put just a teaser in articleBody).
    if len(ld_body) >= 400:
        squash = lambda s: re.sub(r"\W+", "", s).lower()  # noqa: E731
        dom_has_it = squash(ld_body[:120]) in squash(text)
        if not (dom_has_it and len(text) > len(ld_body)):
            text = ld_body
            title = title or ld_title

    # Drop a leading repeat of the title.
    if title and text.startswith(title):
        text = text[len(title):].lstrip(" .\n")
    return title, text


def markdown_to_text(md: str) -> str:
    t = md
    t = re.sub(r"```.*?```", " ", t, flags=re.S)
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", t)              # images
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)            # links -> text
    t = re.sub(r"^\s{0,3}#{1,6}\s*(.+?)\s*#*\s*$", r"\1.", t, flags=re.M)  # headings
    t = re.sub(r"^\s*[-*+]\s+", "", t, flags=re.M)            # bullets
    t = re.sub(r"^\s*>\s?", "", t, flags=re.M)                # quotes
    t = re.sub(r"[*_`]{1,3}", "", t)
    t = re.sub(r"^\s*\|.*\|\s*$", " ", t, flags=re.M)         # tables
    t = re.sub(r"^[=\-]{3,}\s*$", "", t, flags=re.M)
    lines = [ln.strip() for ln in t.split("\n")]
    lines = [ln for ln in lines if not BOILERPLATE_LINE.match(ln)]
    return "\n".join(lines)


def tidy_text(t: str) -> str:
    if not t:
        return ""
    t = html_lib.unescape(t)
    t = t.replace("\r\n", "\n").replace("\r", "\n").replace(" ", " ")
    t = re.sub(r"[ \t\f\v]+", " ", t)
    t = re.sub(r" *\n *", "\n", t)
    t = re.sub(r"\n{3,}", "\n\n", t)
    return t.strip()


# ---------------------------------------------------------------------------
# Text -> speech chunks
# ---------------------------------------------------------------------------

ABBREVIATIONS = [
    (r"\bMr\.", "Mister"), (r"\bMrs\.", "Missus"), (r"\bMs\.", "Miz"), (r"\bDr\.", "Doctor"),
    (r"\bProf\.", "Professor"), (r"\bSr\.", "Senior"), (r"\bJr\.", "Junior"),
    (r"\bGen\.", "General"), (r"\bGov\.", "Governor"), (r"\bSen\.", "Senator"),
    (r"\bRep\.", "Representative"), (r"\bSt\.(?= [A-Z])", "Saint"), (r"\bMt\.", "Mount"),
    (r"\bvs?\.", "versus"), (r"\betc\.", "et cetera"), (r"\be\.g\.", "for example"),
    (r"\bi\.e\.", "that is"), (r"\bapprox\.", "approximately"), (r"\bdept\.", "department"),
    (r"\bNo\.(?= ?\d)", "number"), (r"\bFig\.", "figure"), (r"\bInc\.", "Incorporated"),
    (r"\bLtd\.", "Limited"), (r"\bCo\.(?= )", "Company"), (r"\bCorp\.", "Corporation"),
]
MONTHS = {"Jan": "January", "Feb": "February", "Mar": "March", "Apr": "April",
          "Jun": "June", "Jul": "July", "Aug": "August", "Sep": "September",
          "Sept": "September", "Oct": "October", "Nov": "November", "Dec": "December"}
CURRENCIES = {"$": "dollars", "₹": "rupees", "€": "euros", "£": "pounds", "¥": "yen"}
_SCALE = r"(?:\s?(?:thousand|million|billion|trillion|crore|lakh|k|m|bn|cr)\b)?"
_NUM = r"\d[\d,]*(?:\.\d+)?"


def _currency(m: re.Match) -> str:
    sym, num, scale = m.group(1), m.group(2), (m.group(3) or "").strip()
    scale = {"k": "thousand", "m": "million", "bn": "billion", "cr": "crore"}.get(scale.lower(), scale)
    word = CURRENCIES.get(sym, "rupees")
    if num == "1" and not scale:
        word = word[:-1]
    return f"{num} {scale + ' ' if scale else ''}{word}"


def normalize_for_speech(t: str) -> str:
    t = unicodedata.normalize("NFKC", t)
    t = t.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"')
    t = re.sub(r"https?://\S+|www\.\S+", " ", t)                 # bare URLs
    t = re.sub(r"\[(?:\d+|citation needed|edit)\]", "", t, flags=re.I)  # wiki refs
    t = re.sub(r"[\U0001F000-\U0001FAFF☀-➿️]", " ", t)  # emoji
    t = re.sub(r"[#*_~`|<>^]+", " ", t)
    t = re.sub(r"\band/or\b", "and or", t)
    # Currency: $1,250 -> 1,250 dollars; Rs. 500 / INR 500 -> 500 rupees
    t = re.sub(r"\b(?:Rs\.?|INR)\s?(?=\d)", "₹", t)
    t = re.sub(rf"([$₹€£¥])\s?({_NUM})({_SCALE})", _currency, t, flags=re.I)
    t = re.sub(rf"({_NUM})\s?%", r"\1 percent", t)
    for pat, rep in ABBREVIATIONS:
        t = re.sub(pat, rep, t)
    t = re.sub(r"\b(" + "|".join(MONTHS) + r")\.?(?=\s+\d)", lambda m: MONTHS[m.group(1)], t)
    # U.S.A. -> USA (spelled out letter by letter, and no false sentence break)
    t = re.sub(r"\b((?:[A-Z]\.){2,})", lambda m: m.group(1).replace(".", ""), t)
    t = re.sub(r"\s*&\s*", " and ", t)
    t = re.sub(r"\s*[–—]\s*", ", ", t)  # dashes read as a pause
    t = re.sub(r"\.{3,}|…", "... ", t)
    t = re.sub(r"[ \t]+", " ", t)
    return t.strip()


_SENT_END = re.compile(r"(?<=[.!?।॥])[\"')\]]*\s+(?=[\"'(\[]?[A-Z0-9ऀ-ॿ\"'(])|(?<=[।॥])\s*")


def split_sentences(paragraph: str) -> list[str]:
    parts = [p.strip() for p in _SENT_END.split(paragraph) if p and p.strip()]
    return parts


def _split_long(sentence: str, limit: int) -> list[str]:
    if len(sentence) <= limit:
        return [sentence]
    out, cur = [], ""
    for piece in re.split(r"(?<=[,;:])\s+|\s+-\s+", sentence):
        if len(piece) > limit:  # no commas: cut on word boundaries
            words, piece_parts, buf = piece.split(), [], ""
            for w in words:
                if len(buf) + len(w) + 1 > limit and buf:
                    piece_parts.append(buf)
                    buf = w
                else:
                    buf = f"{buf} {w}".strip()
            if buf:
                piece_parts.append(buf)
        else:
            piece_parts = [piece]
        for pp in piece_parts:
            if len(cur) + len(pp) + 1 > limit and cur:
                out.append(cur)
                cur = pp
            else:
                cur = f"{cur} {pp}".strip()
    if cur:
        out.append(cur)
    return out


def detect_language(text: str) -> str:
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return "en"
    deva = sum(1 for c in letters if "ऀ" <= c <= "ॿ")
    return "hi" if deva / len(letters) > 0.3 else "en"


def chunk_text(text: str, max_chars: int = 280, first_chars: int = 140) -> list[str]:
    """Split into speakable chunks. The first chunk is kept short so audio
    starts quickly; later chunks are longer so the model has context."""
    chunks: list[str] = []
    first_chars = min(first_chars, max_chars)
    for para in re.split(r"\n\s*\n|\n", text):
        para = normalize_for_speech(para)
        if not para:
            continue
        if para[-1] not in ".!?।॥:;,\"')":
            para += "."
        cur = ""
        for sent in split_sentences(para):
            for piece in _split_long(sent, max_chars):
                limit = first_chars if not chunks else max_chars
                if cur and len(cur) + len(piece) + 1 > limit:
                    chunks.append(cur)
                    cur = piece
                else:
                    cur = f"{cur} {piece}".strip()
        if cur:
            chunks.append(cur)  # paragraph end -> chunk boundary (natural pause)
    return chunks


def prepare(text: str, max_chars: int = 280) -> str:
    text = tidy_text(text)
    lang = detect_language(text)
    chunks = chunk_text(text, max_chars=max_chars)
    return json.dumps({"lang": lang, "chunks": chunks}, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Model installation (download + unpack .tar.bz2 from GitHub releases)
# ---------------------------------------------------------------------------

class Cancelled(Exception):
    pass


def _report(sink, stage: str, pct: int):
    if sink is None:
        return
    sink.update(stage, int(pct))
    if sink.isCancelled():
        raise Cancelled()


def download(url: str, path: str, sink=None) -> None:
    part = path + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    headers = {"User-Agent": UA}
    if have:
        headers["Range"] = f"bytes={have}-"
    with requests.get(url, headers=headers, stream=True, timeout=(15, 60), allow_redirects=True) as r:
        if r.status_code == 416:  # already complete
            os.replace(part, path)
            return
        r.raise_for_status()
        if have and r.status_code != 206:  # server ignored Range: restart
            have = 0
        total = int(r.headers.get("Content-Length", "0")) + have
        mode = "ab" if have else "wb"
        done, last = have, -1
        with open(part, mode) as f:
            for chunk in r.iter_content(256 * 1024):
                f.write(chunk)
                done += len(chunk)
                pct = int(done * 100 / total) if total else 0
                if pct != last:
                    _report(sink, "download", pct)
                    last = pct
    os.replace(part, path)


class _CountingReader(io.RawIOBase):
    def __init__(self, f, total, sink):
        self.f, self.total, self.sink, self.done, self.last = f, total, sink, 0, -1

    def readable(self):
        return True

    def readinto(self, b):
        n = self.f.readinto(b)
        self.done += n or 0
        pct = int(self.done * 100 / self.total) if self.total else 0
        if pct != self.last:
            _report(self.sink, "unpack", pct)
            self.last = pct
        return n


def unpack(archive: str, dest_dir: str, keep: list[str], sink=None) -> list[str]:
    """Extract only the files named in `keep` (by basename, or a directory
    prefix ending in '/') flat into dest_dir. Returns the extracted names."""
    os.makedirs(dest_dir, exist_ok=True)
    keep_files = {k for k in keep if not k.endswith("/")}
    total = os.path.getsize(archive)
    extracted = []
    with open(archive, "rb") as raw:
        reader = io.BufferedReader(_CountingReader(raw, total, sink), 1024 * 1024)
        with tarfile.open(fileobj=reader, mode="r|bz2") as tar:
            for m in tar:
                if not m.isfile():
                    continue
                base = os.path.basename(m.name)
                if base not in keep_files or ".." in m.name:
                    continue
                src = tar.extractfile(m)
                if src is None:
                    continue
                out = os.path.join(dest_dir, base)
                with open(out + ".tmp", "wb") as f:
                    while True:
                        buf = src.read(1024 * 1024)
                        if not buf:
                            break
                        f.write(buf)
                os.replace(out + ".tmp", out)
                extracted.append(base)
    return extracted


def install_model(url: str, archive_path: str, dest_dir: str, keep_json: str, sink=None) -> str:
    keep = json.loads(keep_json)
    try:
        if not os.path.exists(archive_path):
            download(url, archive_path, sink)
        got = unpack(archive_path, dest_dir, keep, sink)
        missing = sorted(set(keep) - set(got))
        if missing:
            # A corrupt/partial archive: remove it so the next try re-downloads.
            os.remove(archive_path)
            return _result(False, error="Model archive is missing: " + ", ".join(missing))
        os.remove(archive_path)
        return _result(True, files=got)
    except Cancelled:
        return _result(False, error="cancelled")
    except (tarfile.TarError, EOFError, OSError) as e:
        try:
            os.remove(archive_path)
        except OSError:
            pass
        return _result(False, error=f"Unpacking failed ({e}). Please try again.")
    except requests.RequestException as e:
        return _result(False, error=f"Download failed ({e.__class__.__name__}). "
                                    "It will resume when you retry.")
