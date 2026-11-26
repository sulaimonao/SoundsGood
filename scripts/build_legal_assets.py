#!/usr/bin/env python3
"""Generate offline Personal disclosures from the public documents, without network IO."""
from html.parser import HTMLParser
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

class MainText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.active = False
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag == 'main':
            self.active = True
        if self.active and tag in ('p', 'h1', 'h2', 'li', 'pre'):
            self.parts.append('\n\n')

    def handle_endtag(self, tag):
        if tag == 'main':
            self.active = False

    def handle_data(self, data):
        if self.active:
            self.parts.append(data)

def expected_asset():
    parser = MainText()
    parser.feed((ROOT / 'docs/personal-privacy.html').read_text())
    privacy = ''.join(parser.parts).strip()
    parts = ['SoundsGood Personal Edition — About & legal', privacy,
             (ROOT / 'LICENSE').read_text().strip(),
             (ROOT / 'THIRD_PARTY_NOTICES.md').read_text().strip()]
    for path in sorted((ROOT / 'docs/licenses').glob('*.txt')):
        if path.name != 'Personal-Use-Only-1.0.txt':
            parts.append(path.name + '\n\n' + path.read_text().strip())
    return '\n\n' + ('\n\n' + '=' * 64 + '\n\n').join(parts) + '\n'

if __name__ == '__main__':
    target = ROOT / 'app/src/main/assets/legal/personal-disclosures.txt'
    expected = expected_asset()
    if '--check' in sys.argv:
        assert target.read_text() == expected, 'Offline disclosures need regeneration'
        print('Offline legal asset matches public sources.')
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(expected)
        print('Generated', target.relative_to(ROOT))
