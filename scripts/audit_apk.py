#!/usr/bin/env python3
"""Reject game/BIOS assets and unexpected payloads in the packaged APK.
This inventories payloads; it is not a copyright or trademark clearance.
"""
import gzip, pathlib, sys, zipfile
apk = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    files = [n for n in archive.namelist() if not n.endswith('/')]
    for name in files:
        if not name.startswith('assets/'): continue
        raw = archive.read(name)
        if name.startswith('assets/licenses/'):
            assert len(raw) < 512 * 1024 and b'\x00' not in raw, f'Non-text license: {name}'
        elif name in ('assets/cover-index/nes.tsv.gz', 'assets/cover-index/snes.tsv.gz', 'assets/cover-index/megadrive.tsv.gz'):
            text = gzip.decompress(raw).decode('utf-8')
            assert len(text) < 8 * 1024 * 1024
            for line in text.splitlines():
                key, path = line.split('\t')
                assert path.startswith(('Named_Boxarts/', 'Named_Titles/', 'Named_Snaps/')) and path.endswith('.png')
        elif name in ('assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'): pass
        else: raise AssertionError(f'Unexpected asset: {name}')
    expected = {f'lib/{abi}/{lib}.so' for abi in ('arm64-v8a', 'armeabi-v7a') for lib in ('libfceumm', 'libsnes9x', 'libgenesis', 'liblibretrodroid')}
    assert {n for n in files if n.startswith('lib/')} == expected, 'Unexpected/missing native libraries'
    for core in ('fceumm', 'snes9x', 'genesis'):
        texts = [n for n in files if n.startswith(f'assets/licenses/{core}/')]
        assert texts, f'Missing licenses for {core}'
        if core == 'fceumm': assert any('copying' in n.lower() for n in texts), 'Missing FCEUmm GPL text'
    assert not any(n.lower().endswith(('.nes', '.sfc', '.smc', '.gen', '.smd', '.rom', '.bios', '.bin')) for n in files), 'Unexpected ROM/BIOS file'
    print(f'APK audit passed: {len(files)} entries; only licenses, filename metadata and Android profiles in assets; 8 expected native libraries; no game ROM/BIOS files')
