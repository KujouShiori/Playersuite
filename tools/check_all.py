#!/usr/bin/env python3
"""
静态自检脚本（不需要网络与 NeoForge 依赖）：
1) Java 文件括号平衡与 package 路径一致性
2) Component.translatable 的占位符数量与语言文件是否一致
3) 未使用的 import

用法：在工程根目录执行  python tools/check_all.py
"""
import json
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'main', 'java')
LANG = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'playersuite', 'lang')


def java_files():
    for root, _dirs, files in os.walk(SRC):
        for f in files:
            if f.endswith('.java'):
                yield os.path.join(root, f).replace('\\', '/')


def strip_noise(text):
    s = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    s = re.sub(r'"(\\.|[^"\\])*"', '""', s)
    s = re.sub(r"'(\\.|[^'\\])*'", "''", s)
    return s


def check_structure():
    problems = 0
    for p in java_files():
        text = open(p, encoding='utf-8').read()
        body = strip_noise(text)
        pairs = {'{}': body.count('{') - body.count('}'),
                 '()': body.count('(') - body.count(')'),
                 '[]': body.count('[') - body.count(']')}
        m = re.search(r'package\s+([\w.]+);', body)
        pkg = m.group(1) if m else '?'
        rel = p.split('src/main/java/')[-1]
        expected = os.path.dirname(rel).replace('/', '.')
        for kind, delta in pairs.items():
            if delta != 0:
                print(f"  [括号不平衡 {kind}] {p}: {delta}")
                problems += 1
        if pkg != expected:
            print(f"  [package 不匹配] {p}: {pkg} != {expected}")
            problems += 1
    return problems


def placeholders(s):
    if '%' not in s:
        return 0
    explicit = [int(m) - 1 for m in re.findall(r'%(\d+)\$', s)]
    if explicit:
        return max(explicit) + 1
    return len(re.findall(r'%[sd]', s))


def check_lang():
    langs = {f: json.load(open(f, encoding='utf-8')) for f in glob.glob(os.path.join(LANG, '*.json'))}
    if not langs:
        print("  [警告] 没有找到语言文件")
        return 1
    pat = re.compile(r'Component\.translatable\("([^"]+)"')
    problems = 0
    calls = 0
    for p in java_files():
        txt = open(p, encoding='utf-8').read()
        for m in pat.finditer(txt):
            calls += 1
            key = m.group(1)
            open_idx = txt.rindex('(', m.start(), m.end())
            depth, args, j = 0, 0, open_idx
            in_str, esc = False, False
            while j < len(txt):
                c = txt[j]
                if in_str:
                    if esc:
                        esc = False
                    elif c == '\\':
                        esc = True
                    elif c == '"':
                        in_str = False
                else:
                    if c == '"':
                        in_str = True
                    elif c == '(':
                        depth += 1
                    elif c == ')':
                        depth -= 1
                        if depth == 0:
                            break
                    elif c == ',' and depth == 1:
                        args += 1
                j += 1
            given = args
            # 动态拼接的键（字面量部分以 '.' 结尾，例如 "playersuite.hub.feature." + key）：
            # 只要语言文件里存在该前缀下的任意键就算通过，不做逐字符匹配。
            if key.endswith('.'):
                if not any(k.startswith(key) for d in langs.values() for k in d):
                    print(f"  [动态键无匹配] {key}*  ({p})")
                    problems += 1
                continue
            for f, d in langs.items():
                name = os.path.basename(f)
                if key not in d:
                    print(f"  [缺翻译键] {name}: {key}  ({p})")
                    problems += 1
                elif placeholders(d[key]) != given:
                    print(f"  [占位符不匹配] {name}: {key} 需要 "
                          f"{placeholders(d[key])} 个参数，代码给了 {given} 个  ({p})")
                    problems += 1
    print(f"  translatable 调用 {calls} 处，语言文件 {len(langs)} 份")
    return problems


def check_unused_imports():
    problems = 0
    for p in java_files():
        text = open(p, encoding='utf-8').read()
        body = re.sub(r'^import .*$', '', text, flags=re.M)
        for m in re.finditer(r'^import (?:static )?([\w.]+);', text, re.M):
            simple = m.group(1).split('.')[-1]
            if simple == '*':
                continue
            if not re.search(r'\b' + re.escape(simple) + r'\b', body):
                print(f"  [未使用 import] {p}: {m.group(1)}")
                problems += 1
    return problems


def main():
    total = 0
    for name, fn in (('结构', check_structure), ('语言', check_lang), ('导入', check_unused_imports)):
        print(f'== {name}检查 ==')
        total += fn()
    print()
    if total:
        print(f'发现问题 {total} 处')
        return 1
    print('全部通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())
