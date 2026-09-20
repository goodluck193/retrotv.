#!/usr/bin/env python3
"""Build the exact upstream engine with reviewable RetroTV source changes."""
import pathlib
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
ENGINE = ROOT / '.engine'
REVISION = '8835c3098514390a271e36983957f7bb5f40abf1'  # LibretroDroid 0.14.0

def run(*args, cwd=ENGINE):
    return subprocess.run(args, cwd=cwd, check=True, text=True, capture_output=False)

if not ENGINE.exists():
    run('git', 'clone', '--no-checkout', 'https://github.com/Swordfish90/LibretroDroid.git', str(ENGINE), cwd=ROOT)
head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ENGINE, text=True).strip()
if head != REVISION or not (ENGINE / '.git/index').exists():
    run('git', 'checkout', '--detach', REVISION)
run('git', 'submodule', 'update', '--init', '--depth', '1')
patch = ROOT / 'engine' / 'libretrodroid.patch'
if subprocess.run(['git', 'apply', '--check', str(patch)], cwd=ENGINE, capture_output=True).returncode == 0:
    run('git', 'apply', str(patch))
elif subprocess.run(['git', 'apply', '--reverse', '--check', str(patch)], cwd=ENGINE, capture_output=True).returncode != 0:
    raise SystemExit('Engine differs from expected source. Preserve your changes and prepare a clean .engine checkout.')
for name in ('audio.h', 'audio.cpp', 'tv_audio_buffer.h', 'fpssync.h', 'fpssync.cpp', 'frame_clock.h'):
    shutil.copyfile(ROOT / 'native' / name, ENGINE / 'libretrodroid/src/main/cpp' / name)
shutil.copyfile(ROOT / 'engine/build.gradle.kts', ENGINE / 'libretrodroid/build.gradle.kts')
(ENGINE / 'libretrodroid/build.gradle').unlink(missing_ok=True)
print('Pinned engine prepared:', REVISION)
