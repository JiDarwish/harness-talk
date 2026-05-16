#!/usr/bin/env python3
"""Result unwrap checker -- detects unsafe .value access on Result types.

Scans Python source files for .value attribute access that occurs outside of
a match/case block, which could fail at runtime if the Result is a Failure.

Usage:
    python result_unwrap_checker.py <file.py>
    python result_unwrap_checker.py --self-test
"""

import ast
import sys
import textwrap
import tempfile
import os


class ResultUnwrapVisitor(ast.NodeVisitor):
    """Walks the AST looking for .value access outside of match/case blocks."""

    def __init__(self, filename: str):
        self.filename = filename
        self.findings: list[str] = []
        self._inside_match_case = False

    def visit_Match(self, node: ast.Match):
        """Track when we are inside a match statement."""
        # Visit the subject normally
        self.visit(node.subject)
        # Visit cases with the flag set
        old = self._inside_match_case
        self._inside_match_case = True
        for case in node.cases:
            self.visit(case)
        self._inside_match_case = old

    def visit_Attribute(self, node: ast.Attribute):
        """Detect .value access outside of match/case blocks."""
        if node.attr == "value" and not self._inside_match_case:
            line = node.lineno
            self.findings.append(
                f"ERROR at {self.filename}:{line} -- Unsafe .value access on possible Result type.\n"
                f"  WHY: Accessing .value directly without a match/case guard is unsafe. If the\n"
                f"  Result is actually a Failure, it has no .value attribute and this will raise\n"
                f"  AttributeError at runtime. The Result union type (Success | Failure) exists\n"
                f"  to force callers to handle both variants.\n"
                f"  FIX: Use match/case to safely destructure the Result:\n"
                f"    match result:\n"
                f"        case Success(value):\n"
                f"            # use value safely here\n"
                f"        case Failure(error):\n"
                f"            # handle the error case"
            )
        self.generic_visit(node)


def check(filepath: str) -> list[str]:
    """Parse a Python file and return any findings."""
    with open(filepath, "r") as f:
        source = f.read()
    tree = ast.parse(source, filename=filepath)
    visitor = ResultUnwrapVisitor(filepath)
    visitor.visit(tree)
    return visitor.findings


def run_self_test():
    """Run embedded test cases to verify the checker works."""
    print("Running self-test...")

    bad_code = textwrap.dedent("""\
        from bookshelf.domain.result import Success, Failure

        def handle_result(result):
            # BAD: direct .value access without match/case
            book = result.value
            print(book.title)

        def also_bad(result):
            # BAD: isinstance guard but still not inside match
            if isinstance(result, Success):
                title = result.value.title
                return title
    """)

    good_code = textwrap.dedent("""\
        from bookshelf.domain.result import Success, Failure

        def handle_result(result):
            # GOOD: using match/case
            match result:
                case Success(value):
                    print(value.title)
                case Failure(error):
                    print(error)
    """)

    # Test bad code produces findings
    with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
        f.write(bad_code)
        bad_path = f.name

    try:
        bad_findings = check(bad_path)
        if not bad_findings:
            print("FAIL: Self-test found no issues in known-bad code!")
            sys.exit(1)
        print(f"PASS: Found {len(bad_findings)} issue(s) in known-bad code:")
        for finding in bad_findings:
            first_line = finding.split("\n")[0]
            print(f"  {first_line}")
    finally:
        os.unlink(bad_path)

    # Test good code is clean
    with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
        f.write(good_code)
        good_path = f.name

    try:
        good_findings = check(good_path)
        if good_findings:
            print("FAIL: Self-test found issues in known-good code!")
            for finding in good_findings:
                print(f"  {finding}")
            sys.exit(1)
        print("PASS: No issues found in known-good code.")
    finally:
        os.unlink(good_path)

    print("Self-test passed.")


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "--self-test":
        run_self_test()
        return

    filepath = sys.argv[1]
    if not os.path.exists(filepath):
        print(f"ERROR: File not found: {filepath}")
        sys.exit(1)

    findings = check(filepath)
    if not findings:
        print(f"OK: No Result unwrap issues found in {filepath}")
    else:
        for finding in findings:
            print(finding)
            print()
        sys.exit(1)


if __name__ == "__main__":
    main()
