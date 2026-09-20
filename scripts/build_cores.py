#!/usr/bin/env python3
"""Compile bundled libretro cores from pinned source revisions."""
import json, os, pathlib, shutil, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[1]
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk: raise SystemExit('Set ANDROID_HOME to the Android SDK directory')
ndk = pathlib.Path(sdk) / 'ndk/26.3.11579264'
if not (ndk / 'ndk-build').exists(): raise SystemExit('Install NDK 26.3.11579264 first')
for core in json.loads((ROOT / 'engine/cores.lock.json').read_text()):
    source = ROOT / '.cores' / core['name']
    if not source.exists():
        source.parent.mkdir(exist_ok=True)
        subprocess.run(['git', 'init', str(source)], check=True)
        subprocess.run(['git', 'remote', 'add', 'origin', core['repository']], cwd=source, check=True)
        subprocess.run(['git', 'fetch', '--depth', '1', 'origin', core['revision']], cwd=source, check=True)
    subprocess.run(['git', 'checkout', '--detach', core['revision']], cwd=source, check=True)
    subprocess.run(['git', 'submodule', 'update', '--init', '--recursive', '--depth', '1'], cwd=source, check=True)
    build = source / core['jni']
    subprocess.run([str(ndk / 'ndk-build'), '-C', str(build), '-j2',
        'APP_ABI=arm64-v8a armeabi-v7a', 'APP_PLATFORM=android-26',
        'APP_LDFLAGS=-Wl,-z,max-page-size=16384'], check=True)
    for abi in ('arm64-v8a', 'armeabi-v7a'):
        dest = ROOT / 'app/src/main/jniLibs' / abi / core['library']
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build.parent / 'libs' / abi / 'libretro.so', dest)
    print('Built', core['name'], core['revision'], flush=True)
