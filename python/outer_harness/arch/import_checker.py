#!/usr/bin/env python3
"""Architecture import checker -- enforces layer dependency rules.

Scans Python source files and checks that imports respect the layered
architecture:
- domain/ imports nothing from other app layers (persistence, service, api)
- persistence/ depends on domain/ only (not service or api)
- service/ depends on persistence/ and domain/ (not api)
- Repository classes only in persistence/

Usage:
    python import_checker.py <base_dir>
    python import_checker.py --self-test
"""

import ast
import os
import sys
import tempfile
import textwrap
from pathlib import Path


# The recognized app layers
APP_LAYERS = {"api", "service", "persistence", "domain", "config"}

# Dependency rules: each layer maps to the set of layers it must NOT import from.
FORBIDDEN_IMPORTS = {
    "domain": {"api", "service", "persistence", "config"},
    "persistence": {"api", "service"},
    "service": {"api"},
    # api can import from service and domain (but not persistence directly)
    "api": {"persistence"},
}


def classify_layer(filepath: str, base_dir: str) -> str | None:
    """Determine which app layer a file belongs to based on its path."""
    rel = os.path.relpath(filepath, base_dir)
    parts = Path(rel).parts
    for part in parts:
        if part in APP_LAYERS:
            return part
    return None


def extract_imports(filepath: str) -> list[tuple[int, str]]:
    """Extract all import module names from a Python file, with line numbers."""
    with open(filepath, "r") as f:
        source = f.read()

    try:
        tree = ast.parse(source, filename=filepath)
    except SyntaxError:
        return []

    imports = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                imports.append((node.lineno, alias.name))
        elif isinstance(node, ast.ImportFrom):
            if node.module:
                imports.append((node.lineno, node.module))
    return imports


def import_target_layer(module_name: str) -> str | None:
    """Determine which app layer a module import refers to."""
    parts = module_name.split(".")
    for part in parts:
        if part in APP_LAYERS:
            return part
    return None


def check_repository_placement(filepath: str, base_dir: str) -> list[str]:
    """Check that files containing 'Repository' classes are in persistence/."""
    findings = []
    layer = classify_layer(filepath, base_dir)
    if layer == "persistence":
        return findings

    with open(filepath, "r") as f:
        source = f.read()

    try:
        tree = ast.parse(source, filename=filepath)
    except SyntaxError:
        return findings

    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef) and node.name.endswith("Repository"):
            rel_path = os.path.relpath(filepath, base_dir)
            findings.append(
                f"ERROR at {rel_path}:{node.lineno} -- Repository class '{node.name}' "
                f"defined outside persistence/ package.\n"
                f"  WHY: All repository classes must live in the persistence/ directory.\n"
                f"  This is a team convention that keeps data access code in one place and\n"
                f"  makes the layered architecture predictable.\n"
                f"  FIX: Move '{node.name}' to the persistence/ directory."
            )
    return findings


def check_file(filepath: str, base_dir: str) -> list[str]:
    """Check a single file for architecture violations."""
    findings = []
    layer = classify_layer(filepath, base_dir)
    if layer is None or layer not in FORBIDDEN_IMPORTS:
        return findings

    forbidden = FORBIDDEN_IMPORTS[layer]
    imports = extract_imports(filepath)
    rel_path = os.path.relpath(filepath, base_dir)

    for lineno, module_name in imports:
        target_layer = import_target_layer(module_name)
        if target_layer is not None and target_layer in forbidden:
            findings.append(
                f"ERROR at {rel_path}:{lineno} -- {layer}/ imports from {target_layer}/ "
                f"('{module_name}').\n"
                f"  WHY: The {layer} layer must not depend on the {target_layer} layer.\n"
                f"  Allowed dependencies for {layer}/: "
                f"{_allowed_deps(layer)}.\n"
                f"  This layered architecture ensures that lower layers remain independent\n"
                f"  of higher layers and changes propagate in one direction.\n"
                f"  FIX: Remove the import of '{module_name}'. If {layer}/ needs a type\n"
                f"  from {target_layer}/, that type probably belongs in domain/ instead."
            )

    # Also check repository placement
    findings.extend(check_repository_placement(filepath, base_dir))

    return findings


def _allowed_deps(layer: str) -> str:
    """Return human-readable allowed dependencies for a layer."""
    forbidden = FORBIDDEN_IMPORTS.get(layer, set())
    allowed = APP_LAYERS - forbidden - {layer}
    if not allowed:
        return "none (no imports from other app layers)"
    return ", ".join(sorted(allowed))


def check_directory(base_dir: str) -> list[str]:
    """Recursively check all Python files under base_dir."""
    all_findings = []
    for root, _dirs, files in os.walk(base_dir):
        # Skip __pycache__ directories
        if "__pycache__" in root:
            continue
        for fname in files:
            if fname.endswith(".py"):
                filepath = os.path.join(root, fname)
                all_findings.extend(check_file(filepath, base_dir))
    return all_findings


def run_self_test():
    """Run embedded test cases to verify the checker works."""
    print("Running self-test...")

    with tempfile.TemporaryDirectory() as tmpdir:
        # Create a minimal project structure
        for layer in ["domain", "service", "persistence", "api"]:
            os.makedirs(os.path.join(tmpdir, layer))

        # BAD: domain imports from service
        bad_domain = textwrap.dedent("""\
            from bookshelf.service.book_service import BookService
        """)
        with open(os.path.join(tmpdir, "domain", "models.py"), "w") as f:
            f.write(bad_domain)

        # BAD: service imports from api
        bad_service = textwrap.dedent("""\
            from bookshelf.api.book_routes import router
        """)
        with open(os.path.join(tmpdir, "service", "book_service.py"), "w") as f:
            f.write(bad_service)

        # BAD: repository class in service/
        bad_repo_placement = textwrap.dedent("""\
            class BookRepository:
                pass
        """)
        with open(os.path.join(tmpdir, "service", "bad_repo.py"), "w") as f:
            f.write(bad_repo_placement)

        # GOOD: service imports from persistence and domain
        good_service = textwrap.dedent("""\
            from bookshelf.domain.models import Book
            from bookshelf.persistence.book_repository import BookRepository
        """)
        with open(os.path.join(tmpdir, "service", "good_service.py"), "w") as f:
            f.write(good_service)

        # GOOD: persistence imports from domain only
        good_persistence = textwrap.dedent("""\
            from bookshelf.domain.models import Book
        """)
        with open(os.path.join(tmpdir, "persistence", "book_repository.py"), "w") as f:
            f.write(good_persistence)

        findings = check_directory(tmpdir)

        if len(findings) < 3:
            print(f"FAIL: Expected at least 3 findings, got {len(findings)}")
            for finding in findings:
                print(f"  {finding.split(chr(10))[0]}")
            sys.exit(1)

        print(f"PASS: Found {len(findings)} violation(s) in known-bad code:")
        for finding in findings:
            first_line = finding.split("\n")[0]
            print(f"  {first_line}")

        # Verify good code does not trigger
        with tempfile.TemporaryDirectory() as good_tmpdir:
            os.makedirs(os.path.join(good_tmpdir, "service"))
            os.makedirs(os.path.join(good_tmpdir, "persistence"))
            with open(os.path.join(good_tmpdir, "service", "svc.py"), "w") as f:
                f.write(good_service)
            with open(os.path.join(good_tmpdir, "persistence", "repo.py"), "w") as f:
                f.write(good_persistence)

            good_findings = check_directory(good_tmpdir)
            if good_findings:
                print("FAIL: Found violations in known-good code!")
                for finding in good_findings:
                    print(f"  {finding.split(chr(10))[0]}")
                sys.exit(1)

            print("PASS: No violations found in known-good code.")

    print("Self-test passed.")


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "--self-test":
        run_self_test()
        return

    base_dir = sys.argv[1]
    if not os.path.isdir(base_dir):
        print(f"ERROR: Directory not found: {base_dir}")
        sys.exit(1)

    findings = check_directory(base_dir)
    if not findings:
        print(f"OK: No architecture violations found in {base_dir}")
    else:
        for finding in findings:
            print(finding)
            print()
        sys.exit(1)


if __name__ == "__main__":
    main()
