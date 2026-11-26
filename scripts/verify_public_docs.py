#!/usr/bin/env python3
"""Check the complete static site and the offline disclosure dependency closure."""
from html.parser import HTMLParser
from hashlib import sha256
from pathlib import Path
from urllib.parse import urlsplit, unquote
from build_legal_assets import expected_asset

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / 'docs'
EXPECTED = {'index.html', 'full-privacy.html', 'full-terms.html', 'personal-privacy.html',
            'personal-license.html', 'support.html', 'mac-companion.html', 'third-party.html'}
VOID = {'meta', 'link', 'br', 'hr', 'img', 'input', 'source', 'wbr'}
APPROVED_IMAGES = {
    'images/soundsgood.png': 'cee127dba8fae3ea00dc7d36498746666151b6c9f3dd6d254a4503b7a26bd121',
    'images/01-library.png': '2f1e54849570e7735293fc0a3bb1ccfc286cb1fb3c81e0b384c9c23bd5f74a8c',
    'images/05-player.png': '680b6d7a96841c18de4edcae85d687bd1fb22058edd07de60513b0fbd1832785',
    'images/06-journal.png': '2e2a58b56aea0d2e25721c630a8117b3ee816eb0270ca3501777688b40ea0f09',
}

class Check(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path = path
        self.stack = []
        self.headings = 0
        self.main_count = 0
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        assert tag not in {'script', 'iframe', 'form', 'object', 'embed'}, (self.path, tag)
        assert not any(key.startswith('on') for key in attrs), 'Inline event handler'
        if tag == 'html':
            assert attrs.get('lang') == 'en'
        if tag == 'h1': self.headings += 1
        if tag == 'main': self.main_count += 1
        for key in ('href', 'src'):
            if key not in attrs: continue
            url = urlsplit(attrs[key])
            assert url.scheme in {'', 'https', 'mailto'}, (self.path, attrs[key])
            if tag != 'a': assert not url.netloc and not url.scheme, 'Remote asset'
            if not url.scheme and url.path:
                target = (self.path.parent / unquote(url.path)).resolve()
                assert target.is_relative_to(DOCS.resolve()), 'Link escapes docs'
                assert target.is_file(), ('Broken link', self.path.name, attrs[key])
        if tag not in VOID: self.stack.append(tag)
    def handle_endtag(self, tag):
        assert self.stack and self.stack.pop() == tag, ('Invalid nesting', self.path, tag)

assert {p.name for p in DOCS.glob('*.html')} == EXPECTED
assert (DOCS / '.nojekyll').is_file()
for path in sorted(DOCS.rglob('*')):
    assert not path.is_symlink(), 'Symlink in published content'
    if not path.is_file(): continue
    if path.relative_to(DOCS).as_posix() in APPROVED_IMAGES:
        assert sha256(path.read_bytes()).hexdigest() == APPROVED_IMAGES[path.relative_to(DOCS).as_posix()], path
        continue
    assert path.suffix in {'.html', '.css', '.txt', '.json'} or path.name == '.nojekyll', path
    content = path.read_text()
    for marker in ('/Users/', 'BEGIN PRIVATE KEY', 'PREREQUISITE:', 'TODO', 'PLACEHOLDER'):
        assert marker not in content, (path.name, marker)
    if path.suffix == '.html':
        check = Check(path)
        check.feed(content)
        assert not check.stack and check.headings == 1 and check.main_count == 1, path
        assert 'thedream.akeems@gmail.com' in content
        assert 'Akeem Oluwadamilare Sulaimon' in content
assert (ROOT / 'LICENSE').read_text() == (DOCS / 'licenses/Personal-Use-Only-1.0.txt').read_text()
assert (ROOT / 'app/src/main/assets/legal/personal-disclosures.txt').read_text() == expected_asset()
assert 'END OF TERMS AND CONDITIONS' in (DOCS / 'licenses/Apache-2.0.txt').read_text()
ofl = (DOCS / 'licenses/OFL-1.1.txt').read_text()
assert all(s in ofl for s in ('5) The Font Software', 'TERMINATION', 'DISCLAIMER'))
print('PASS: 8 static pages; local links, HTML structure, no active content/remote assets,')
print('public-file safeguards, complete base license texts, and exact offline disclosure consistency.')
