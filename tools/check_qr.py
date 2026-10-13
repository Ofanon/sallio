"""Decodes the QR codes QrCodeTest wrote (app/build/qr/) with OpenCV and compares
them with what was encoded. Run after: ./gradlew testDebugUnitTest --tests "*QrCodeTest"

Usage: python tools/check_qr.py [app/build/qr]
"""
import sys
from pathlib import Path

import cv2

folder = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/build/qr')
detector = cv2.QRCodeDetector()
failures = 0
for line in (folder / 'expected.tsv').read_text(encoding='utf-8').splitlines():
    name, expected = line.split('\t', 1)
    image = cv2.imread(str(folder / name))
    text, _, _ = detector.detectAndDecode(image)
    ok = text == expected
    failures += not ok
    print(('OK  ' if ok else 'FAIL'), name, repr(text[:60]))
print('all decoded' if failures == 0 else f'{failures} failed')
sys.exit(1 if failures else 0)
