#!/usr/bin/env python3
"""Inject freshly built .so files from the vendored AndroidUSBCamera-3.3.3 tree
into libausbc/libs/libuvc-3.2.9.aar (the AAR the Hawkeye Viewer app actually
packages its native libs from). Run from the project root:  python tools/inject_fresh_so.py
Backs up the AAR first (libuvc-3.2.9.aar.bak-<date>). Safe to re-run."""
import zipfile, shutil, os, time

P = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
aar = os.path.join(P, 'libausbc', 'libs', 'libuvc-3.2.9.aar')
libs = os.path.join(P, 'AndroidUSBCamera-3.3.3', 'AndroidUSBCamera-3.3.3',
                    'libuvc', 'src', 'main', 'libs')
backup = aar + '.bak-' + time.strftime('%Y%m%d')
if not os.path.exists(backup):
    shutil.copyfile(aar, backup)
    print('Backed up AAR to', os.path.basename(backup))

names = ['libusb100.so', 'libuvc.so', 'libUVCCamera.so', 'libjpeg-turbo1500.so']
fresh = {}
for abi in ['arm64-v8a', 'armeabi-v7a']:
    for n in names:
        p = os.path.join(libs, abi, n)
        if not os.path.exists(p):
            raise SystemExit('ABORT: missing fresh lib: ' + p)
        # AAR entries use backslash paths - keep them identical
        fresh['jni\\%s\\%s' % (abi, n)] = open(p, 'rb').read()

zin = zipfile.ZipFile(aar)
tmp = aar + '.tmp'
replaced = []
with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename in fresh:
            replaced.append('%s (%d -> %d bytes)' % (item.filename, len(data), len(fresh[item.filename])))
            data = fresh[item.filename]
        zout.writestr(item, data)
zin.close()
os.replace(tmp, aar)
print('Replaced %d entries:' % len(replaced))
for r in replaced:
    print('  ' + r)
print('Done. Rebuild the app so the new AAR gets packaged.')
