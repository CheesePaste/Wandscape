import os

SKIP = {
    r'src\main\java\com\wsteam\wandscape\shared\log\Log.java',
    r'src\main\java\com\wsteam\wandscape\task\engine\dsl\BlueprintInterpreter.java',
}

def is_string_char(c):
    return c in ('"', "'")


def remove_statements(src):
    """Remove balanced `Log.debug(...)`; statements (string-aware)."""
    out = []
    i = 0
    n = len(src)
    removed = 0
    while i < n:
        idx = src.find('Log.debug(', i)
        if idx == -1:
            out.append(src[i:])
            break
        line_start = src.rfind('\n', 0, idx) + 1
        out.append(src[i:line_start])
        j = idx + len('Log.debug(')
        depth = 1
        quote = None
        while j < n and depth > 0:
            c = src[j]
            if quote is not None:
                if c == '\\':
                    j += 2
                    continue
                if c == quote:
                    quote = None
            elif c in ('"', "'"):
                quote = c
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            j += 1
        k = j
        while k < n and src[k] in ' \t':
            k += 1
        if k < n and src[k] == ';':
            k += 1
        if k < n and src[k] == '\n':
            k += 1
        i = k
        removed += 1
    return ''.join(out), removed


def main():
    total_removed = 0
    for root in (r'src\main\java', r'src\test\java'):
        for dirpath, dirnames, filenames in os.walk(root):
            for fn in filenames:
                if not fn.endswith('.java'):
                    continue
                path = os.path.join(dirpath, fn).replace('\\', os.sep)
                key = path.replace(os.sep, '\\')
                if key in SKIP:
                    continue
                with open(path, 'r', encoding='utf-8') as f:
                    src = f.read()
                new_src, removed = remove_statements(src)
                if removed:
                    with open(path, 'w', encoding='utf-8', newline='') as f:
                        f.write(new_src)
                    total_removed += removed
                    print(f'{removed:3d}  {key}')
    print(f'TOTAL REMOVED: {total_removed}')


if __name__ == '__main__':
    main()
