"""Reference implementation of the espeak -> Kokoro v1.0 ("misaki") phoneme
mapping used by app/src/main/java/app/readaloud/MisakiMap.kt (keep the two
identical; they were cross-checked on 4,452 espeak outputs)."""
import re

COMMON = [("ʔn̩", "tᵊn"), ("ʔn", "tᵊn"), ("ʔ", "t"), ("aɪ", "I"), ("aʊ", "W"), ("eɪ", "A"),
          ("ɔɪ", "Y"), ("dʒ", "ʤ"), ("tʃ", "ʧ"), ("ɐ", "ə"), ("r", "ɹ"), ("x", "k"),
          ("ç", "k"), ("ɬ", "l"), ("ʲ", "")]
US = [("ɜːɹ", "ɜɹ"), ("ɜː", "ɜɹ"), ("oːɹ", "ɔɹ"), ("oʊ", "O"), ("əʊ", "O"), ("ɪə", "iə"),
      ("ɚ", "əɹ")] + COMMON + [("ː", "")]
GB = [("eə", "ɛː"), ("əʊ", "Q"), ("oʊ", "Q"), ("iə", "ɪə")] + COMMON
OTHER = [("aɪ", "I"), ("aʊ", "W"), ("dz", "ʣ"), ("dʒ", "ʤ"), ("eɪ", "A"), ("oʊ", "O"),
         ("əʊ", "Q"), ("ts", "ʦ"), ("tʃ", "ʧ"), ("ɔɪ", "Y")]
CONS = "bcdfghjklmnpqstvwxzðθʃʒŋɡɹɾʤʧ"
BOUNDARY = r'$|[ ,.!?;:—…"]'
SYLLABIC_L = re.compile(rf'(?<=[{CONS}])əl(?=$|[ zds,.!?;:—…"])')
SYLLABIC_N = re.compile(rf"(?<=[{CONS}])ən(?={BOUNDARY}|[tdsz](?={BOUNDARY}))")


def to_misaki(ps: str, dialect: str) -> str:
    rules = {"us": US, "gb": GB}.get(dialect, OTHER)
    for a, b in rules:
        ps = ps.replace(a, b)
    if dialect in ("us", "gb"):
        ps = SYLLABIC_L.sub("ᵊl", ps)
        ps = SYLLABIC_N.sub("ᵊn", ps)
    return ps
