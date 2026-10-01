#!/usr/bin/env python3
"""Compile every GLSL shader embedded in Shaders.kt with glslangValidator (GLSL ES 3.00 semantics).

Usage: tools/check_shaders.py [path/to/glslangValidator]
Exit code 1 when any shader fails. CI runs this so a reserved word or a type error in a shader can
never ship again (a failing shader disables the whole effects pipeline on the phone).
"""
import os, re, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app/src/main/kotlin/com/ultrax26/recorder/effects/gl/Shaders.kt')

def main():
    validator = sys.argv[1] if len(sys.argv) > 1 else (shutil.which('glslangValidator') or shutil.which('glslang'))
    if not validator:
        print('glslangValidator not found'); return 2
    src = open(SRC, encoding='utf-8').read()
    pat = re.compile(r'(?:private\s+)?(?:const\s+)?val\s+([A-Z_]+)\s*=\s*"""(.*?)"""', re.S)
    blocks = {m.group(1): m.group(2) for m in pat.finditer(src)}
    common = blocks.get('COMMON', '')
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, body in blocks.items():
            if name == 'COMMON':
                continue
            code = body.replace('$COMMON', common).replace("${'$'}", '$')
            path = os.path.join(tmp, name + ('.vert' if name.startswith('VERTEX') else '.frag'))
            with open(path, 'w', encoding='utf-8') as f:
                f.write(code)
            r = subprocess.run([validator, path], capture_output=True, text=True)
            if r.returncode != 0:
                failures += 1
                print(f'FAIL {name}')
                for line in (r.stdout + r.stderr).splitlines():
                    if 'ERROR' in line or 'WARNING' in line:
                        print('    ', line)
            else:
                print(f'OK   {name}')
    print(f'{len(blocks) - 1} shaders checked, {failures} failing')
    return 1 if failures else 0

if __name__ == '__main__':
    sys.exit(main())
