"""
Simple prototype metagenerator utility (Python).

Usage (from repo root):
  python tools\metagenerator.py --meta <meta-file> --data-dir <files-fpml/data> --report <report.json> [--update-meta] [--backup]

What it does:
- Scans scheme files in data dir (files named schemes*.xml and additionalDefinitions.xml)
- Parses <scheme ... canonicalUri="..."> entries to build canonicalUri -> filename map
- Parses the given meta XML and collects all fpml:schemeUri values under fpml:schemeDefault
- Produces a JSON report listing matched and unmatched schemeUris and which file provides them
- If --update-meta is passed, adds <fpml:schemes> entries for scheme files that provide matches but are not already referenced in the meta file. It writes a backup if --backup is used.

This is a prototype to validate scheme defaults against the release schemes files.
"""
import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

# Namespaces used in meta files
NS = {
    'fpml': 'urn:HandCoded:FpML-Releases',
    'meta': 'urn:HandCoded:Releases'
}

def find_scheme_files(data_dir):
    candidates = []
    for entry in os.listdir(data_dir):
        if entry.startswith('schemes') and entry.endswith('.xml'):
            candidates.append(os.path.join(data_dir, entry))
        elif entry == 'additionalDefinitions.xml':
            candidates.append(os.path.join(data_dir, entry))
    return sorted(candidates)

def parse_schemes_file(path):
    # parse large XML but only look for <scheme ... canonicalUri="..."> attributes
    canonical_uris = set()
    try:
        for event, elem in ET.iterparse(path, events=('start',)):
            if elem.tag.endswith('scheme'):
                # element attributes
                canon = elem.attrib.get('canonicalUri') or elem.attrib.get('canonicaluri')
                uri = elem.attrib.get('uri')
                if canon:
                    canonical_uris.add(canon.strip())
                elif uri:
                    # try to reduce version suffix to canonical
                    # e.g. http://.../account-type-1-1 -> http://.../account-type
                    # naive heuristic: strip trailing -digit groups
                    p = uri.rsplit('-', 2)[0]
                    canonical_uris.add(p)
    except ET.ParseError:
        # fallback full parse
        tree = ET.parse(path)
        root = tree.getroot()
        for elem in root.iter():
            if elem.tag.endswith('scheme'):
                canon = elem.attrib.get('canonicalUri') or elem.attrib.get('canonicaluri')
                uri = elem.attrib.get('uri')
                if canon:
                    canonical_uris.add(canon.strip())
                elif uri:
                    p = uri.rsplit('-', 2)[0]
                    canonical_uris.add(p)
    return canonical_uris

def build_canonical_map(data_dir):
    files = find_scheme_files(data_dir)
    mapping = defaultdict(list)  # canonicalUri -> [filenames]
    for f in files:
        try:
            uris = parse_schemes_file(f)
        except Exception as e:
            print(f"Warning: failed to parse {f}: {e}", file=sys.stderr)
            uris = set()
        for u in uris:
            mapping[u].append(os.path.relpath(f))
    return mapping

def parse_meta_scheme_uris(meta_path):
    tree = ET.parse(meta_path)
    root = tree.getroot()
    # find all fpml:schemeDefault/fpml:schemeUri (namespace aware)
    scheme_uris = []
    # build namespace map for searches
    for sd in root.findall('.//{'+NS['fpml']+'}schemeDefault'):
        su = sd.find('{'+NS['fpml']+'}schemeUri')
        if su is not None and su.text:
            scheme_uris.append(su.text.strip())
    return scheme_uris, tree

def ensure_fpml_schemes_in_meta(tree, meta_path, files_to_add, backup=False):
    # tree is ElementTree; add <fpml:schemes> elements (text content is filename relative path)
    root = tree.getroot()
    # find existing fpml:schemes values
    existing = set()
    for el in root.findall('.//{'+NS['fpml']+'}schemes'):
        if el.text:
            existing.add(el.text.strip())
    added = []
    for f in files_to_add:
        if f not in existing:
            # append before closing schemaRelease, but after other fpml:schemeDefault entries ideally
            # We'll append just before the last child to keep simple
            new_el = ET.Element('{'+NS['fpml']+'}schemes')
            new_el.text = f
            root.append(new_el)
            added.append(f)
    if added:
        if backup:
            bak = meta_path + '.bak'
            try:
                os.replace(meta_path, bak)
            except Exception:
                # fallback copy
                import shutil
                shutil.copy2(meta_path, bak)
        # write back
        tree.write(meta_path, encoding='utf-8', xml_declaration=False)
    return added


def main(argv):
    p = argparse.ArgumentParser(description='Prototype metagenerator: verify/cross-reference schemeUris in meta files against schemes files')
    p.add_argument('--meta', required=True, help='Meta XML file to analyze (e.g. files-fpml/meta/fpml-5-13-confirmation.generated.xml)')
    p.add_argument('--data-dir', required=True, help='Data directory containing schemes files (e.g. files-fpml/data)')
    p.add_argument('--report', required=True, help='JSON report output path')
    p.add_argument('--update-meta', action='store_true', help='If set, update the meta file to add <fpml:schemes> entries for matched scheme files')
    p.add_argument('--backup', action='store_true', help='When updating meta, save a .bak')
    args = p.parse_args(argv)

    data_dir = args.data_dir
    meta = args.meta
    report_path = args.report

    if not os.path.isdir(data_dir):
        print('Error: data-dir not found: ' + data_dir, file=sys.stderr)
        return 2
    if not os.path.isfile(meta):
        print('Error: meta file not found: ' + meta, file=sys.stderr)
        return 2

    mapping = build_canonical_map(data_dir)
    scheme_uris, tree = parse_meta_scheme_uris(meta)

    report = {
        'metaFile': meta,
        'dataDir': data_dir,
        'foundSchemeFiles': {},
        'matched': {},
        'unmatched': []
    }

    # record which files contain canonical uris
    for k, v in mapping.items():
        report['foundSchemeFiles'][k] = v

    files_that_match = set()
    for su in sorted(set(scheme_uris)):
        if su in mapping:
            report['matched'][su] = mapping[su]
            for f in mapping[su]:
                files_that_match.add(f)
        else:
            # attempt small heuristic: strip trailing -digit groups
            heuristic = su.rsplit('-', 2)[0]
            if heuristic in mapping:
                report['matched'][su] = mapping[heuristic]
                for f in mapping[heuristic]:
                    files_that_match.add(f)
            else:
                report['unmatched'].append(su)

    # If update requested, add fpml:schemes entries for matched files
    added = []
    if args.update_meta and files_that_match:
        # we want relative paths like files-fpml/data/filename
        # mapping stored relative paths already
        # ensure unique order
        files_to_add = sorted(files_that_match)
        added = ensure_fpml_schemes_in_meta(tree, meta, files_to_add, backup=args.backup)

    report['addedSchemesEntries'] = added

    with open(report_path, 'w', encoding='utf-8') as fo:
        json.dump(report, fo, indent=2)

    print('Report written to', report_path)
    print('Matched:', len(report['matched']), 'Unmatched:', len(report['unmatched']))
    if added:
        print('Added <fpml:schemes> entries:', added)
    return 0

if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))

