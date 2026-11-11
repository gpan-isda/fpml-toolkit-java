"""Download an FpML set of coding schemes and every code list it references.

Usage (from the toolkit root):
    python tools/fetch-codelists.py 2-27 tools/codelist5-14

Writes set-of-schemes-<set>.xml and one genericode file per scheme into the
output directory, ready for:
    java -cp tools Metagenerator --convert-codelist-dir <dir> --out-schemes files-fpml/data/schemes5-<n>.xml
         --set-of-schemes <dir>/set-of-schemes-<set>.xml --overwrite
"""
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

BASE = "https://www.fpml.org/coding-scheme/"


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read()


def main(set_version, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    set_name = f"set-of-schemes-{set_version}.xml"
    data = fetch(BASE + set_name)
    with open(os.path.join(out_dir, set_name), "wb") as f:
        f.write(data)

    root = ET.fromstring(data)
    refs = [el for el in root.iter() if el.tag.endswith("CodeListRef")]
    failed = []
    for ref in refs:
        loc = next((el.text.strip() for el in ref.iter() if el.tag.endswith("LocationUri") and el.text), None)
        if not loc:
            continue
        # Some entries omit the .xml extension (e.g. hedge-type-1-0).
        url = loc.replace("http://", "https://", 1)
        if not url.endswith(".xml"):
            url += ".xml"
        name = url.rsplit("/", 1)[-1]
        try:
            with open(os.path.join(out_dir, name), "wb") as f:
                f.write(fetch(url))
        except Exception as e:
            failed.append(f"{url}: {e}")
    print(f"{len(refs)} code lists in {set_name}; {len(refs) - len(failed)} downloaded to {out_dir}")
    for line in failed:
        print("FAILED", line)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
