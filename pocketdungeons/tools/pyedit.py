"""CRLF-preserving edit helpers for scripted source changes.

Source files in this repo are CRLF. Rewriting them with a plain open().write() flips the endings and makes
every diff a whole-file diff. These helpers read either ending, edit on LF text, and write the file back in the
ending it came with.

Use from a small script file (large shell heredocs with embedded quotes have failed here):

    import sys
    sys.path.insert(0, r'A:\\MrPinoys Mods\\pocketdungeons\\tools')
    from pyedit import edit, write, remove_method

    edit(r'A:\\MrPinoys Mods\\pocketdungeons\\src\\main\\java\\pocketdungeons\\Foo.java', [
        ('old text', 'new text'),       # each old text must exist; only the first match is replaced
    ])
"""

import re


def read(path):
    """Return (text with LF endings, whether the file was CRLF)."""
    raw = open(path, encoding='utf-8', newline='').read()
    return raw.replace('\r\n', '\n'), '\r\n' in raw


def save(path, text, crlf):
    open(path, 'w', encoding='utf-8', newline='').write(text.replace('\n', '\r\n') if crlf else text)


def edit(path, steps):
    """Apply (old, new) replacements in order; fail loudly if an old text is missing."""
    text, crlf = read(path)
    for old, new in steps:
        if old not in text:
            raise AssertionError('%s: text not found: %r' % (path, old[:90]))
        text = text.replace(old, new, 1)
    save(path, text, crlf)


def write(path, text, crlf=True):
    """Write a new file (CRLF by default, like the rest of the tree)."""
    save(path, text.replace('\r\n', '\n'), crlf)


def remove_method(path, name):
    """Delete a method by name, with its javadoc, annotations and one trailing blank line.

    Compile afterwards: if anything still calls it, javac says so.
    """
    text, crlf = read(path)
    lines = text.split('\n')
    pattern = re.compile(r'\s+(public |private |protected )?(static )?(final )?[\w<>\[\], .?]+\s+%s\(' % re.escape(name))
    idx = next((i for i, l in enumerate(lines) if pattern.match(l)), None)
    if idx is None:
        raise AssertionError('%s: no method %s' % (path, name))
    depth, end, seen = 0, None, False
    for j in range(idx, len(lines)):
        for ch in lines[j]:
            if ch == '{':
                depth += 1
                seen = True
            elif ch == '}':
                depth -= 1
        if seen and depth == 0:
            end = j
            break
    start = idx
    k = idx - 1
    while k >= 0 and lines[k].strip().startswith(('@', '*', '/**', '*/')):
        start = k
        k -= 1
    if end + 1 < len(lines) and lines[end + 1].strip() == '':
        end += 1
    del lines[start:end + 1]
    save(path, '\n'.join(lines), crlf)
