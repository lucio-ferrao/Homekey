#!/usr/bin/env python3
"""
Makes app/src/main/assets/emoji/nomes.txt, the names and keywords of the emojis in English and Portuguese for the
emoji mode of the suggestion strip, from the CLDR annotations (Unicode license): one line per emoji, the emoji
(without the variation selector FE0F), a tab and its names separated by "|". Skin tone variants are left out.
Run from the root of the repository: python3 tools/emoji-nomes.py
"""
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

CLDR = "https://raw.githubusercontent.com/unicode-org/cldr/main/common"
LANGUAGES = ["en", "pt", "pt_PT"]  # pt is the Portuguese of Brazil, pt_PT has the differences
SKIN_TONES = {chr(c) for c in range(0x1F3FB, 0x1F400)}

names = {}
for language in LANGUAGES:
    for folder in ("annotations", "annotationsDerived"):
        try:
            root = ET.fromstring(urllib.request.urlopen(f"{CLDR}/{folder}/{language}.xml").read())
        except urllib.error.HTTPError:
            continue  # pt_PT has no derived annotations

        for annotation in root.iter("annotation"):
            emoji = annotation.get("cp").replace("️", "")
            if any(c in SKIN_TONES for c in emoji) or not annotation.text:
                continue
            words = [annotation.text.strip()] if annotation.get("type") == "tts" else \
                [w.strip() for w in annotation.text.split("|")]
            entry = names.setdefault(emoji, [])
            entry.extend(w for w in words if w and w not in entry)

with open("app/src/main/assets/emoji/nomes.txt", "w", encoding="utf-8") as out:
    for emoji, words in names.items():
        out.write(emoji + "\t" + "|".join(words) + "\n")
print(len(names), "emojis")
