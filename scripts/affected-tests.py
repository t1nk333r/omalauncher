#!/usr/bin/env python3
"""Prints the Gradle test tasks and `--tests` filters for the unit tests a change can affect.

    ./gradlew $(scripts/affected-tests.py) :app:assembleDebug

It splits Kotlin into units: each top-level function or property, and each class's header, init code and
members. A unit changed when its code did, comments and spacing aside; a deleted or renamed one counts too, so a
test that no longer compiles still runs. From each test class it follows what the code can run: the functions
and properties it names (same package, imported or fully qualified), a class's header when it names the class,
and the class's members only once something constructs it (or names it, for an object or enum class), so a
type passed around doesn't drag in everything the class does. Companion members and nested classes count when
named as `Class.member`, from inside the class or by import. A test runs when what it reaches changed, or when
it reads a changed file by path (`src/main/assets/widgets`).

Gradle scripts (except version bumps), the version catalog, the wrapper, test resources and this script run every
test. Resources, native code, docs and device tests run none, since unit tests don't load them. With nothing
affected it prints nothing, which skips the test tasks and compiling the tests.

The change is from the merge base of --base (default: the branch's upstream) to the working tree, untracked files
included, or to --head. A base git can't find runs every test.
"""
import argparse
import os
import posixpath
import re
import subprocess
import sys
from collections import defaultdict

SOURCE_SETS = {
    "owl/src/main/kotlin/": "owl-main",
    "owl/src/cli/kotlin/": "owl-cli",
    "owl/src/test/kotlin/": "owl-test",
    "app/src/main/kotlin/": "app-main",
    "app/src/test/kotlin/": "app-test",
}
# What each source set compiles against: the app uses the owl library, and tests use what they test.
SEES = {
    "owl-main": {"owl-main"},
    "owl-cli": {"owl-main", "owl-cli"},
    "owl-test": {"owl-main", "owl-test"},
    "app-main": {"owl-main", "app-main"},
    "app-test": {"owl-main", "app-main", "app-test"},
}
TEST_TASKS = {"owl-test": (":owl:test", "owl"), "app-test": (":app:testDebugUnitTest", "app")}

RUN_ALL = re.compile(
    r"(^|/)(build\.gradle\.kts|settings\.gradle\.kts|gradle\.properties|gradlew|gradlew\.bat)$"
    r"|^gradle/|^(app|owl)/src/test/(?!kotlin/)|^scripts/affected-tests\.py$"
)
DECL = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*((?:[a-z]+\s+)*?)(fun\s+interface|class|interface|object|fun|val|var|typealias)\s+(.*)")
IMPORT = re.compile(r"^import\s+([\w.]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*$", re.M)
PATH_LITERAL = re.compile(r'"((?:\.\./|src/)[^"$]*)')
IDENT = re.compile(r"[A-Za-z_]\w*")
MEMBER = re.compile(r"^\s+(?:@[\w.]+(?:\([^)]*\))?\s+)*((?:[a-z]+\s+)*?)(fun\s+interface|class|interface|object|fun|val|var|typealias|init|constructor)\b\s*(.*)")
CALL = re.compile(r"\b([A-Za-z_]\w*)\s*(?:<[^()]*?>)?\s*\(")
QUALIFIED = re.compile(r"\b([A-Z]\w*)\s*\.\s*([A-Za-z_]\w*)")


def git(*args, check=True):
    run = subprocess.run(["git", *args], capture_output=True, text=True)
    if run.returncode:
        if check:
            raise RuntimeError(f"git {' '.join(args)}: {run.stderr.strip()}")
        return None
    return run.stdout


def source_set(path):
    return next((s for prefix, s in SOURCE_SETS.items() if path.startswith(prefix)), None)


def code_only(text, keep_strings=False):
    """`text` with comments and string contents blanked, keeping newlines and the code in `$name` and `${...}`.
    With `keep_strings` only comments are blanked, which is what a change is compared on."""
    out, n = list(text), len(text)

    def blank(a, b, literal=True):
        if literal and keep_strings:
            return
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    def code(i, in_template=False):
        depth = 0
        while i < n:
            c, two = text[i], text[i:i + 2]
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                blank(i, end, literal=False)
                i = end
            elif two == "/*":
                start, nest, i = i, 1, i + 2
                while i < n and nest:
                    nest += {"/*": 1, "*/": -1}.get(text[i:i + 2], 0)
                    i += 2 if text[i:i + 2] in ("/*", "*/") else 1
                blank(start, i, literal=False)
            elif text.startswith('"""', i):
                i = string(i + 3, raw=True)
            elif c == '"':
                i = string(i + 1, raw=False)
            elif c == "'":
                end = i + 1
                if end < n and text[end] == "\\":
                    end += 2
                while end < n and text[end] != "'":
                    end += 1
                blank(i, end + 1)
                i = end + 1
            elif in_template and c == "{":
                depth += 1
                i += 1
            elif in_template and c == "}":
                if depth == 0:
                    blank(i, i + 1)
                    return i + 1
                depth -= 1
                i += 1
            else:
                i += 1
        return i

    def string(i, raw):
        start = i
        while i < n:
            if raw and text.startswith('"""', i):
                while text.startswith('""""', i):
                    i += 1
                blank(start, i)
                return i + 3
            if not raw and text[i] == '"':
                blank(start, i)
                return i + 1
            if not raw and text[i] == "\\":
                i += 2
            elif text[i] == "$" and text[i + 1:i + 2] == "{":
                blank(start, i + 2)
                i = code(i + 2, in_template=True)
                start = i
            elif text[i] == "$" and IDENT.match(text, i + 1):
                blank(start, i + 1)
                i = IDENT.match(text, i + 1).end()
                start = i
            else:
                i += 1
        blank(start, i)
        return i

    code(0)
    return "".join(out)


def declared(keyword, rest):
    """(name, is extension) of a declaration: `fun <T> List<T>.name(` gives ("name", True)."""
    if keyword not in ("fun", "val", "var"):
        m = re.match(r"\w+", rest)
        return (m.group(0) if m else None), False
    head = rest
    while (stripped := re.sub(r"<[^<>]*>", "", head)) != head:
        head = stripped
    head = head.split("(")[0] if keyword == "fun" else re.match(r"\s*([\w.?`]*)", head).group(1)
    names = re.findall(r"\w+", head)
    return (names[-1] if names else None), "." in head.strip()



# Members a class's code runs without naming them: operators, delegates, iteration, equality and printing.
IMPLICIT = re.compile(r"(equals|hashCode|toString|compareTo|iterator|hasNext|next|component\d+|getValue|setValue|provideDelegate|invoke|get|set|contains|plus|minus)$")


class Unit:
    """A piece of code that changes as one: a top-level function or property, or one part of a class (its header,
    its init code, or one member), with the names its code uses."""

    def __init__(self, file, owner, kind, name, modifiers, extension, code, source, static=False):
        self.file, self.owner, self.kind, self.name, self.extension = file, owner, kind, name, extension
        self.private = "private" in modifiers
        self.operator = "operator" in modifiers
        # Companion members and nested classes need only the class's name, not an instance.
        self.static = static or kind in ("class", "alias")
        self.code = code
        self.normalized = " ".join(source.split())  # comments and spacing don't count; literals do
        self.bare, self.dotted = set(), set()
        for m in IDENT.finditer(code):
            before = code[max(0, m.start() - 2):m.start()]
            dotted = before.endswith(".") and not before.endswith("..")
            (self.dotted if dotted else self.bare).add(m.group(0))
        self.tokens = self.bare | self.dotted
        # Constructor calls and references: `Foo(`, `Foo<T>(` and `::Foo`.
        self.calls = set(CALL.findall(code)) | set(re.findall(r"::\s*([A-Za-z_]\w*)", code))
        self.qualified = set(QUALIFIED.findall(code))  # (Class, member) pairs, completed by imports in Kt

    @property
    def key(self):
        part = self.kind if self.kind in ("header", "init") else "member"
        return (self.owner.name if self.owner else None, part, self.name)

    def label(self):
        name = {"header": "header", "init": "init"}.get(self.kind, self.name)
        return f"{posixpath.basename(self.file.path)} ({self.owner.name + '.' if self.owner else ''}{name})"


class Cls:
    """A top-level class, interface or object; its parts are Units."""

    def __init__(self, file, keyword, name, modifiers):
        self.file, self.keyword, self.name = file, keyword, name
        self.private = "private" in modifiers
        # Naming an object or an enum class initialises it, as calling a constructor does.
        self.initialised_by_name = keyword == "object" or "enum" in modifiers
        self.units, self.supertypes = [], set()

    def finish(self):
        header = next(u for u in self.units if u.kind == "header")
        self.header = header
        # The supertype list: after the name, its type parameters and its primary constructor.
        code, at = header.code, re.search(rf"\b{re.escape(self.name or '')}\b", header.code)
        i = at.end() if at else len(code)
        for open_, close in (("<", ">"), ("(", ")")):
            while i < len(code) and code[i].isspace():
                i += 1
            if code[i:i + 1] == open_:
                depth = 0
                while i < len(code):
                    depth += (code[i] == open_) - (code[i] == close)
                    i += 1
                    if depth == 0:
                        break
        rest = code[i:].lstrip()
        if rest.startswith(":"):
            self.supertypes = set(IDENT.findall(re.split(r"[{]|\bwhere\b", rest[1:], maxsplit=1)[0]))
        self.statics = defaultdict(list)
        for u in self.units:
            if u.static and u.name:
                self.statics[u.name].append(u)
        self.made_units = [u for u in self.units if u.kind in ("init", "prop") and not u.static
                           or u.operator or (u.name and IMPLICIT.match(u.name))]


def split_class(file, cls, lines, kept, modifiers, extension):
    """A class's header (up to the body's brace), init code (init blocks, constructors, the companion object's
    line) and members, each member including the annotations above it."""
    units, pending = [], []
    current = {"kind": "header", "name": None, "modifiers": modifiers, "extension": extension, "static": False, "rows": []}
    brace = paren = 0
    body = False
    companion = None  # the brace depth inside the companion object, while in it

    def flush():
        if current["rows"]:
            units.append(Unit(file, cls, current["kind"], current["name"], current["modifiers"], current["extension"],
                              "\n".join(lines[r] for r in current["rows"]), "\n".join(kept[r] for r in current["rows"]),
                              current["static"]))

    for row, line in enumerate(lines):
        member_level = body and paren == 0 and (brace == 1 or brace == companion)
        m = MEMBER.match(line) if member_level else None
        if m and m.group(2) == "object" and "companion" not in m.group(1) and not re.match(r"\w", m.group(3)):
            m = None  # `object : Callback() {`, an expression continuing the member above
        if m:
            flush()
            keyword, mods = m.group(2), m.group(1).split()
            if keyword in ("init", "constructor") or (keyword == "object" and "companion" in mods):
                current = {"kind": "init", "name": None, "modifiers": mods, "extension": False, "static": keyword == "object", "rows": []}
                if keyword == "object":
                    companion = brace + 1
            else:
                name, ext = declared(keyword, m.group(3))
                kind = {"fun": "fun", "val": "prop", "var": "prop", "typealias": "alias"}.get(keyword, "class")
                current = {"kind": kind, "name": name, "modifiers": mods, "extension": ext,
                           "static": brace == companion, "rows": []}
            current["rows"] += pending + [row]
            pending = []
        elif member_level and line.lstrip().startswith("@"):
            pending.append(row)
        else:
            current["rows"] += pending + [row]
            pending = []
        for c in line:
            if c in "([":
                paren += 1
            elif c in ")]":
                paren -= 1
            elif c == "{":
                body = body or (brace == 0 and paren == 0)
                brace += 1
            elif c == "}":
                brace -= 1
        if companion is not None and brace < companion and paren == 0:
            companion = None
    current["rows"] += pending
    flush()
    return units


class Kt:
    """A Kotlin file: its package, imports, classes and units."""

    def __init__(self, path, text):
        self.path, self.set, self.text = path, source_set(path), text
        code = code_only(text)
        self.package = next(iter(re.findall(r"^package\s+([\w.]+)", code, re.M)), "")
        self.imports, self.star_imports, self.import_names = set(), set(), set()
        for name, star, alias in IMPORT.findall(code):
            if star:
                self.star_imports.add(name)
            else:
                self.imports.add(name)
                self.import_names.add((name, alias or name.rsplit(".", 1)[-1]))
        lines, kept = code.split("\n"), code_only(text, keep_strings=True).split("\n")
        starts, depth, pending = [], 0, None
        for number, line in enumerate(lines):
            if depth == 0 and line[:1].strip():
                m = DECL.match(line)
                if m:
                    starts.append((number if pending is None else pending, m))
                    pending = None
                elif line.startswith("@") and not line.startswith("@file:"):
                    pending = number if pending is None else pending
                elif not line.startswith(("}", ")", "]")):
                    pending = None
            depth += sum(line.count(c) for c in "{([") - sum(line.count(c) for c in "})]")
        self.units, self.classes = [], []
        for k, (start, m) in enumerate(starts):
            end = starts[k + 1][0] if k + 1 < len(starts) else len(lines)
            keyword, modifiers = m.group(2), m.group(1).split()
            name, extension = declared(keyword, m.group(3))
            if keyword in ("class", "interface", "object") or keyword.startswith("fun") and "interface" in keyword:
                cls = Cls(self, keyword, name, modifiers)
                cls.units = split_class(self, cls, lines[start:end], kept[start:end], modifiers, extension)
                cls.finish()
                self.classes.append(cls)
                self.units += cls.units
            else:
                kind = {"fun": "fun", "val": "prop", "var": "prop"}.get(keyword, "alias")
                self.units.append(Unit(self, None, kind, name, modifiers, extension, "\n".join(lines[start:end]),
                                       "\n".join(kept[start:end])))
        for path, short in self.import_names:
            parts = path.split(".")
            owner = parts[-3] if len(parts) > 2 and parts[-2] == "Companion" else parts[-2] if len(parts) > 1 else None
            for u in self.units:
                if owner and short in u.bare:
                    u.qualified.add((owner, parts[-1]))
        self.top_props = [u for u in self.units if u.owner is None and u.kind == "prop"]
        self.tests = [c for c in self.classes if self.set in TEST_TASKS and c.keyword == "class"
                      and any("@Test" in u.code for u in c.units)]

    def sees(self, file, name, private):
        """Whether code in this file can name a top-level `name` declared in `file`."""
        if file.path == self.path:
            return True
        if private or file.set not in SEES[self.set]:
            return False
        qualified = f"{file.package}.{name}"
        return (
            self.package == file.package
            or any(i == qualified or i.startswith(qualified + ".") for i in self.imports)
            or any(i == file.package or i == qualified or i.startswith(qualified + ".") for i in self.star_imports)
            or qualified in self.text
        )


class Graph:
    """Every unit, and for each the top-level units and classes its code names."""

    def __init__(self, files):
        top, classes, self.members = defaultdict(list), defaultdict(list), defaultdict(list)
        for f in files:
            for c in f.classes:
                classes[c.name].append(c)
            for u in f.units:
                (self.members[u.name] if u.owner else top[u.name]).append(u)
        for f in files:
            for u in f.units:
                u.names_top = [v for t in u.tokens for v in top.get(t, ()) if v is not u
                               and (t in u.bare or v.extension or v.operator) and f.sees(v.file, v.name, v.private)]
                u.names_classes = [(c, t in u.calls or c.initialised_by_name) for t in u.bare for c in classes.get(t, ())
                                   if f.sees(c.file, c.name, c.private)]
        self.classes = classes

    def reach(self, start):
        """Every unit code starting at `start` can run or compile against, each with the unit that led to it.

        Naming a class reaches only its header, since a type alone runs nothing. Its instance members count once
        something constructs it (or names it, for an object or enum class), and its companion members and nested
        classes once code names them as `Class.member`, from inside the class or by import."""
        parent, tokens, named, made, loaded, queue = {}, set(), set(), set(), set(), []
        expanded, wanted = set(), defaultdict(set)

        def add(u, why, expand=True):
            if id(u) not in parent:
                parent[id(u)] = (u, why)
            if expand and id(u) not in expanded:
                expanded.add(id(u))
                queue.append(u)

        def make(c, why):
            if c in made:
                return
            made.add(c)
            add(c.header, why)
            for u in c.made_units:
                add(u, why)
            for u in c.units:
                if not u.static and u.kind in ("fun", "prop") and u.name in tokens:
                    add(u, why)
            for s in c.supertypes:
                for sc in self.classes.get(s, ()):
                    if c.file.sees(sc.file, sc.name, sc.private):
                        make(sc, why)

        for u in start:
            add(u, None)
        while queue:
            u = queue.pop()
            new = u.tokens - tokens
            tokens |= new
            if u.owner is None and u.file.path not in loaded:
                loaded.add(u.file.path)  # a file's top-level properties initialise together
                for p in u.file.top_props:
                    add(p, u)
            for v in u.names_top:
                add(v, u)
            for c, constructs in u.names_classes:
                if c not in named:
                    named.add(c)
                    for member in wanted[c.name]:
                        for s in c.statics.get(member, ()):
                            add(s, u)
                add(c.header, u, expand=False)
                if constructs:
                    make(c, u)
            for owner, member in u.qualified:
                wanted[owner].add(member)
                for c in self.classes.get(owner, ()):
                    if c in named:
                        for s in c.statics.get(member, ()):
                            add(s, u)
            if u.owner is not None:
                for t in u.bare:
                    for s in u.owner.statics.get(t, ()):
                        add(s, u)
            for t in new:
                for m in self.members.get(t, ()):
                    if m.owner in made and not m.static:
                        add(m, u)
        return parent, tokens


def changed_units(old, new):
    """Units of one file whose code differs between `old` and `new` (either may be None)."""
    if old is None or new is None or old.package != new.package or old.star_imports != new.star_imports:
        return [u for f in (old, new) if f for u in f.units]

    def by_key(f):
        groups = defaultdict(list)
        for u in f.units:
            groups[u.key].append(u.normalized)
        return {key: sorted(texts) for key, texts in groups.items()}

    before, after = by_key(old), by_key(new)
    keys = {k for k in before.keys() | after.keys() if before.get(k) != after.get(k)}
    if any(k[1] == "member" and k[2] is None for k in keys):
        return old.units + new.units  # a part it couldn't name changed: count the whole file
    changed = [u for f in (old, new) for u in f.units if u.key in keys]
    # An import added or dropped changes what a name means to the units using it.
    for _, short in old.import_names ^ new.import_names:
        changed += [u for u in new.units if short in u.tokens]
    return changed


def read(paths, ref):
    """Contents in the working tree, or at `ref`; paths missing there are left out."""
    out = {}
    if ref is None:
        for p in paths:
            try:
                with open(p, encoding="utf-8", errors="replace") as f:
                    out[p] = f.read()
            except FileNotFoundError:
                pass
        return out
    if not paths:
        return out
    batch = subprocess.run(["git", "cat-file", "--batch"], input="".join(f"{ref}:{p}\n" for p in paths).encode(),
                           capture_output=True, check=True).stdout
    pos = 0
    for p in paths:
        end = batch.index(b"\n", pos)
        header, pos = batch[pos:end].split(), end + 1
        if header[-1] == b"missing":
            continue
        size = int(header[2])
        out[p] = batch[pos:pos + size].decode("utf-8", "replace")
        pos += size + 1
    return out


def version_bump(path, base, head):
    """Whether the change to a build script only sets versionCode and versionName, as every release does, and comments."""
    diff = git("diff", "-U0", base, *([head] if head else []), "--", path)
    lines = [l[1:] for l in diff.splitlines() if l[:1] in ("+", "-") and not l.startswith(("+++", "---"))]
    return bool(lines) and all(re.match(r"\s*(version(Code|Name)\s*=|//)", l) for l in lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", help="ref the change starts from (default: the branch's upstream)")
    parser.add_argument("--head", help="ref the change ends at (default: the working tree)")
    parser.add_argument("--all", action="store_true", help="print every test task")
    parser.add_argument("-q", "--quiet", action="store_true", help="no summary on stderr")
    args = parser.parse_args()
    say = (lambda *_: None) if args.quiet else (lambda *a: print(*a, file=sys.stderr))

    def run_all(reason):
        say(f"affected tests: all ({reason})")
        print(" ".join(task for task, _ in TEST_TASKS.values()))

    if args.all:
        return run_all("--all")
    try:
        os.chdir(git("rev-parse", "--show-toplevel").strip())
        base = (args.base or git("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}", check=False) or "").strip()
        if not base:
            return run_all("no --base, and the branch has no upstream")
        merge_base = (git("merge-base", base, args.head or "HEAD", check=False) or "").strip()
        if not merge_base:
            return run_all(f"no merge base with {base}")
        if args.head:
            changed = git("diff", "--name-only", "--no-renames", merge_base, args.head).splitlines()
            listed = git("ls-tree", "-r", "--name-only", args.head).splitlines()
        else:
            changed = git("diff", "--name-only", "--no-renames", merge_base).splitlines()
            changed += git("ls-files", "--others", "--exclude-standard").splitlines()
            listed = git("ls-files", "--cached", "--others", "--exclude-standard").splitlines()
        changed = sorted(set(changed))
        for path in changed:
            if (RUN_ALL.search(path) and not version_bump(path, merge_base, args.head)) \
                    or (source_set(path) and not path.endswith(".kt")):
                return run_all(f"{path} changed")
        now = read([p for p in listed if p.endswith(".kt") and source_set(p)], args.head)
        before = read([p for p in changed if p.endswith(".kt") and source_set(p)], merge_base)
    except (RuntimeError, subprocess.CalledProcessError) as e:
        return run_all(str(e))

    files = {p: Kt(p, text) for p, text in now.items()}
    changed_keys, removed = set(), set()
    for p in changed:
        if p in files or p in before:
            for u in changed_units(Kt(p, before[p]) if p in before else None, files.get(p)):
                changed_keys.add((u.file.path, u.key))
    present = {(u.file.path, u.key) for f in files.values() for u in f.units}
    for path, key in changed_keys - present:
        removed.add(key[0] if key[1] == "header" else key[2])  # a deleted or renamed declaration's old name
    removed.discard(None)

    graph = Graph(files.values())
    selected = {}
    for f in files.values():
        for test in f.tests:
            parent, tokens = graph.reach(test.units)
            hit = next((u for u, _ in parent.values() if (u.file.path, u.key) in changed_keys), None)
            if hit:
                steps, u = [], hit
                while u is not None and u.owner is not test:
                    steps.append(u.label())
                    u = parent[id(u)][1]
                selected[test] = " <- ".join(reversed(steps)) or "changed"
            elif tokens & removed:
                selected[test] = f"names {', '.join(sorted(tokens & removed))}, which the change removes"
        if f.tests and not any(t in selected for t in f.tests):
            for literal in PATH_LITERAL.findall(f.text):
                target = posixpath.normpath(posixpath.join(TEST_TASKS[f.set][1], literal))
                hit = next((p for p in changed if p == target or p.startswith(target + "/")), None)
                if hit and not target.startswith(".."):
                    selected.update({t: f"reads {literal} ({hit})" for t in f.tests})
                    break

    total = sum(len(f.tests) for f in files.values())
    say(f"affected tests: {len(changed)} changed files since {base} ({merge_base[:7]}), {len(selected)} of {total} test classes")
    out = []
    for test_set, (task, _) in TEST_TASKS.items():
        tests = sorted((t for t in selected if t.file.set == test_set), key=lambda t: (t.file.path, t.name))
        if tests:
            out.append(task)
        for t in tests:
            say(f"  {task} {t.name} -> {selected[t]}")
            out += ["--tests", f"{t.file.package}.{t.name}"]
    if not out:
        say("  none: the test tasks are skipped")
    print(" ".join(out))


if __name__ == "__main__":
    main()
