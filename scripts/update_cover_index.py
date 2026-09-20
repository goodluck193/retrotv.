#!/usr/bin/env python3
"""Refresh filename metadata only; no artwork or game data is bundled."""
import gzip, json, pathlib, re, urllib.request, unicodedata
ROOT = pathlib.Path(__file__).resolve().parents[1]
REPOS = {
    'nes': 'Nintendo_-_Nintendo_Entertainment_System',
    'snes': 'Nintendo_-_Super_Nintendo_Entertainment_System',
    'megadrive': 'Sega_-_Mega_Drive_-_Genesis',
}
def normalize(title):
    title = re.sub(r'\([^)]*\)|\[[^]]*\]', '', title)
    title = unicodedata.normalize('NFKD', title).encode('ascii', 'ignore').decode().lower().replace('&', ' and ')
    title = re.sub(r'[^a-z0-9]+', ' ', title).strip()
    if title.endswith(' the'): title = title[:-4]
    if title.startswith('the '): title = title[4:]
    return title

def main():
    folder = ROOT / 'app/src/main/assets/cover-index'; folder.mkdir(parents=True, exist_ok=True)
    sources = []
    for system, repo in REPOS.items():
        def fetch(url):
            req = urllib.request.Request(url, headers={'User-Agent': 'RetroConsole-cover-index'})
            with urllib.request.urlopen(req, timeout=45) as response: return json.load(response)
        tree = fetch(f'https://api.github.com/repos/libretro-thumbnails/{repo}/git/trees/master?recursive=1')
        if tree.get('truncated'): raise RuntimeError('Incomplete catalog')
        rows = []
        for entry in tree['tree']:
            path = entry['path']
            if entry['type'] != 'blob' or not path.endswith('.png') or path.split('/')[0] not in ('Named_Boxarts', 'Named_Titles', 'Named_Snaps'): continue
            title = pathlib.PurePosixPath(path).stem
            rows.append((normalize(title), path))
        rows.sort(key=lambda x: (x[0], ('Named_Boxarts','Named_Titles','Named_Snaps').index(x[1].split('/')[0]), 0 if 'USA' in x[1] else 1 if 'World' in x[1] else 2 if 'Europe' in x[1] else 3, x[1]))
        data = ''.join(f'{key}\t{path}\n' for key,path in rows).encode()
        (folder / f'{system}.tsv.gz').write_bytes(gzip.compress(data, mtime=0))
        sources.append({'system':system,'repository':f'https://github.com/libretro-thumbnails/{repo}','tree':tree['sha'],'entries':len(rows)})
        print(system, len(rows), len(data), flush=True)
    (ROOT / 'engine/cover-sources.json').write_text(json.dumps(sources, indent=2)+'\n')
if __name__ == '__main__': main()
