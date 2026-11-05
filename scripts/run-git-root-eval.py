"""Run real Git root tests with a stale EDT cache fixture, against any compiled runtime.

The same compiled tests and cache fixture can be used with the installed baseline
and the candidate. Production classes are taken only from --runtime, never copied
or reimplemented here. All logs and the exact classpath are preserved in --output.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("tree", "runtime", "edt", "jdk", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    options = parser.parse_args()
    for path in (options.tree, options.runtime, options.edt, options.jdk):
        if not path.is_dir():
            parser.error(f"Directory does not exist: {path}")
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "tests"
    classes.mkdir(exist_ok=True)
    plugins = options.edt / "plugins"
    dependencies = [p for p in plugins.iterdir()
                    if (p.is_dir() or p.suffix == ".jar")
                    and not p.name.startswith(("javax.xml_", "javax.xml.stream_"))]
    dependencies += list((options.tree / "bundles/com.codepilot1c.core/lib").glob("*.jar"))
    junit = list(plugins.glob("org.junit_4*"))
    if not junit:
        parser.error("JUnit 4 is missing from EDT plugins")
    source = options.tree / "bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/git/GitRepositoryRootTest.java"
    fixture = options.tree / "scripts/eval-stubs/git-root/com/_1c/g5/v8/dt/common/git/GitUtils.java"
    classpath = os.pathsep.join(p.resolve().as_posix() for p in [options.runtime, *dependencies])
    compile_args = output / "compile.args"
    compile_args.write_text('\n'.join([
        '-encoding UTF-8', '-cp', json.dumps(classpath), '-d', json.dumps(classes.as_posix()),
        json.dumps(source.resolve().as_posix()), json.dumps(fixture.resolve().as_posix()),
    ]), encoding="utf-8")
    with (output / "compile.log").open("wb") as log:
        compiled = subprocess.run([str(options.jdk / "bin/javac"), "@" + str(compile_args)], stdout=log, stderr=subprocess.STDOUT)
    if compiled.returncode:
        print((output / "compile.log").read_text(errors="replace"))
        return compiled.returncode
    runtime_classpath = os.pathsep.join([classes.as_posix(), classpath])
    runtime_args = output / "runtime.args"
    runtime_args.write_text('-Dfile.encoding=UTF-8\n-cp\n' + json.dumps(runtime_classpath)
                            + '\norg.junit.runner.JUnitCore\ncom.codepilot1c.core.git.GitRepositoryRootTest\n', encoding="utf-8")
    with (output / "tests.log").open("wb") as log:
        tested = subprocess.run([str(options.jdk / "bin/java"), "@" + str(runtime_args)], stdout=log, stderr=subprocess.STDOUT)
    text = (output / "tests.log").read_text(encoding="utf-8", errors="replace")
    summary = re.findall(r"^(?:OK \(\d+ tests?\)|Tests run:.*)$", text, flags=re.M)
    result = {"runtime": str(options.runtime.resolve()), "returncode": tested.returncode,
              "summary": summary, "fixture": str(fixture), "production_source_reimplemented": False,
              "log": str(output / "tests.log")}
    (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    return tested.returncode or (0 if summary == ["OK (7 tests)"] else 2)


if __name__ == "__main__":
    sys.exit(main())
