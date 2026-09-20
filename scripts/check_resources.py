#!/usr/bin/env python3
"""Validate complete locales and references without needing an Android SDK."""
import collections, gzip, pathlib, re, xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
LOCALES = ('values', 'values-ru', 'values-es', 'values-pt', 'values-fr', 'values-de', 'values-it')
FORMAT = re.compile(r'%\d+\$(?:\.\d+)?[sdf]')
def strings(folder):
    items = ET.parse(RES / folder / 'strings.xml').getroot()
    data = {node.attrib['name']: ''.join(node.itertext()) for node in items}
    assert len(data) == len(items), f'Duplicate strings: {folder}'
    return data
base = strings('values')
for locale in LOCALES:
    other = strings(locale)
    assert other.keys() == base.keys(), (locale, base.keys() - other.keys(), other.keys() - base.keys())
    for key in base:
        assert collections.Counter(FORMAT.findall(base[key])) == collections.Counter(FORMAT.findall(other[key])), (locale, key)
for path in RES.rglob('*.xml'): ET.parse(path)
for path in (ROOT / 'app/src/main/java').rglob('*.kt'):
    for key in re.findall(r'R\.string\.(\w+)', path.read_text()):
        if key != 'ok': assert key in base, (path, key)
for path in (RES / 'layout').glob('*.xml'):
    for key in re.findall(r'@string/(\w+)', path.read_text()): assert key in base, (path, key)
    assert not re.search('[А-Яа-яЁё]', path.read_text()), path
index = ROOT / 'app/src/main/assets/cover-index'
for path in index.glob('*.gz'):
    with gzip.open(path, 'rt') as stream:
        for line in stream:
            key, name = line.rstrip('\n').split('\t')
            assert name.startswith(('Named_Boxarts/', 'Named_Titles/', 'Named_Snaps/')) and name.endswith('.png')
            assert '..' not in name.split('/'), name
with gzip.open(index / 'megadrive.tsv.gz', 'rt') as stream:
    assert any(line.startswith('desert demolition starring road runner and wile e coyote\tNamed_Boxarts/') for line in stream)
print(f'{len(LOCALES)} complete locales × {len(base)} strings; XML, format arguments and cover metadata passed')
