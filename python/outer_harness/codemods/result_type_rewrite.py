#!/usr/bin/env python3
"""Result type rewrite codemod -- transforms functions from raising exceptions
to returning Result types.

Rewrites:
- raise SomeError(...) -> return Failure(SomeError(...))
- return value -> return Success(value)
- Return type annotation T -> Result[T, BookshelfError]
- Adds necessary imports (Success, Failure from domain.result)

Usage:
    python result_type_rewrite.py <file.py> <function_name>
    python result_type_rewrite.py --self-test
"""

import sys
import os
import textwrap
import tempfile

try:
    import libcst as cst
    import libcst.matchers as m
except ImportError:
    print("ERROR: libcst is required. Install it with: pip install libcst")
    sys.exit(1)


class ResultTypeRewriter(cst.CSTTransformer):
    """Transforms a specific function from raising exceptions to returning Result."""

    def __init__(self, function_name: str):
        self.function_name = function_name
        self._inside_target = False
        self._original_return_type: str | None = None
        self._did_rewrite = False
        self._needs_success_import = False
        self._needs_failure_import = False

    def visit_FunctionDef(self, node: cst.FunctionDef) -> bool:
        if node.name.value == self.function_name:
            self._inside_target = True
        return True

    def leave_FunctionDef(
        self, original_node: cst.FunctionDef, updated_node: cst.FunctionDef
    ) -> cst.FunctionDef:
        if original_node.name.value != self.function_name:
            return updated_node

        self._inside_target = False

        # Update return type annotation
        if updated_node.returns is not None:
            ann = updated_node.returns.annotation
            self._original_return_type = _annotation_to_str(ann)

            # Check if already a Result type
            if "Result" in self._original_return_type:
                return updated_node

            new_annotation = cst.Subscript(
                value=cst.Name("Result"),
                slice=[
                    cst.SubscriptElement(slice=cst.Index(value=ann)),
                    cst.SubscriptElement(
                        slice=cst.Index(value=cst.Name("BookshelfError"))
                    ),
                ],
            )
            updated_node = updated_node.with_changes(
                returns=updated_node.returns.with_changes(annotation=new_annotation)
            )
            self._did_rewrite = True

        return updated_node

    def leave_Raise(
        self, original_node: cst.Raise, updated_node: cst.Raise
    ) -> cst.BaseSmallStatement | cst.RemovalSentinel:
        if not self._inside_target:
            return updated_node

        if updated_node.exc is not None:
            self._needs_failure_import = True
            self._did_rewrite = True
            return cst.Return(
                value=cst.Call(
                    func=cst.Name("Failure"),
                    args=[cst.Arg(value=updated_node.exc)],
                )
            )
        return updated_node

    def leave_Return(
        self, original_node: cst.Return, updated_node: cst.Return
    ) -> cst.Return:
        if not self._inside_target:
            return updated_node

        if updated_node.value is not None:
            # Don't wrap if already wrapped in Success/Failure
            if m.matches(
                updated_node.value,
                m.Call(func=m.OneOf(m.Name("Success"), m.Name("Failure"))),
            ):
                return updated_node

            self._needs_success_import = True
            self._did_rewrite = True
            return updated_node.with_changes(
                value=cst.Call(
                    func=cst.Name("Success"),
                    args=[cst.Arg(value=updated_node.value)],
                )
            )
        return updated_node


class ImportAdder(cst.CSTTransformer):
    """Adds Success, Failure, and Result imports if not already present."""

    def __init__(self, need_success: bool, need_failure: bool):
        self._need_success = need_success
        self._need_failure = need_failure
        self._found_result_import = False

    def leave_ImportFrom(
        self, original_node: cst.ImportFrom, updated_node: cst.ImportFrom
    ) -> cst.ImportFrom:
        # Check if this is a domain.result import
        module_str = ""
        if updated_node.module is not None:
            module_str = _module_to_str(updated_node.module)

        if "domain.result" in module_str and isinstance(
            updated_node.names, (list, tuple)
        ):
            self._found_result_import = True
            existing_names = {
                alias.name.value
                for alias in updated_node.names
                if isinstance(alias, cst.ImportAlias)
            }

            new_aliases = list(updated_node.names)
            added = []

            if self._need_success and "Success" not in existing_names:
                new_aliases.append(
                    cst.ImportAlias(name=cst.Name("Success"))
                )
                added.append("Success")

            if self._need_failure and "Failure" not in existing_names:
                new_aliases.append(
                    cst.ImportAlias(name=cst.Name("Failure"))
                )
                added.append("Failure")

            if "Result" not in existing_names:
                new_aliases.append(
                    cst.ImportAlias(name=cst.Name("Result"))
                )
                added.append("Result")

            if added:
                # Ensure commas between all aliases
                final_aliases = []
                for i, alias in enumerate(new_aliases):
                    if i < len(new_aliases) - 1:
                        alias = alias.with_changes(
                            comma=cst.MaybeSentinel.DEFAULT
                        )
                    else:
                        alias = alias.with_changes(
                            comma=cst.MaybeSentinel.DEFAULT
                        )
                    final_aliases.append(alias)

                return updated_node.with_changes(names=final_aliases)

        return updated_node

    def leave_Module(
        self, original_node: cst.Module, updated_node: cst.Module
    ) -> cst.Module:
        if self._found_result_import:
            return updated_node

        # Need to add a brand new import line
        names_to_import = []
        if self._need_success:
            names_to_import.append("Success")
        if self._need_failure:
            names_to_import.append("Failure")
        names_to_import.append("Result")

        aliases = [
            cst.ImportAlias(
                name=cst.Name(n), comma=cst.MaybeSentinel.DEFAULT
            )
            for n in names_to_import
        ]

        import_stmt = cst.SimpleStatementLine(
            body=[
                cst.ImportFrom(
                    module=cst.Attribute(
                        value=cst.Attribute(
                            value=cst.Name("bookshelf"), attr=cst.Name("domain")
                        ),
                        attr=cst.Name("result"),
                    ),
                    names=aliases,
                )
            ]
        )

        return updated_node.with_changes(
            body=[import_stmt] + list(updated_node.body)
        )


def _annotation_to_str(node: cst.BaseExpression) -> str:
    """Convert a CST annotation node to a string."""
    return cst.parse_module("").code_for_node(node).strip()


def _module_to_str(node) -> str:
    """Convert a module attribute chain to dotted string."""
    if isinstance(node, cst.Attribute):
        return _module_to_str(node.value) + "." + node.attr.value
    elif isinstance(node, cst.Name):
        return node.value
    return str(node)


def rewrite_file(filepath: str, function_name: str) -> bool:
    """Rewrite a function in a file to use Result types. Returns True if changes were made."""
    with open(filepath, "r") as f:
        source = f.read()

    tree = cst.parse_module(source)

    # Step 1: Rewrite the function
    rewriter = ResultTypeRewriter(function_name)
    modified = tree.visit(rewriter)

    if not rewriter._did_rewrite:
        return False

    # Step 2: Add imports
    adder = ImportAdder(rewriter._needs_success_import, rewriter._needs_failure_import)
    modified = modified.visit(adder)

    with open(filepath, "w") as f:
        f.write(modified.code)

    return True


def run_self_test():
    """Run embedded test cases to verify the codemod works."""
    print("Running self-test...")

    input_code = textwrap.dedent("""\
        from bookshelf.domain.errors import BookNotFound

        class BookService:
            def find_book(self, book_id: int) -> Book:
                book = self._book_repository.find_by_id(book_id)
                if book is None:
                    raise BookNotFound(id=book_id)
                return book
    """)

    with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
        f.write(input_code)
        temp_path = f.name

    try:
        rewritten = rewrite_file(temp_path, "find_book")

        if not rewritten:
            print("FAIL: Self-test could not rewrite method")
            sys.exit(1)

        with open(temp_path, "r") as f:
            output = f.read()

        checks = {
            "Result[Book, BookshelfError]": "Result return type",
            "Success(": "Success wrapper",
            "Failure(": "Failure wrapper",
        }

        all_passed = True
        for pattern, description in checks.items():
            if pattern in output:
                print(f"  PASS: Found {description}")
            else:
                print(f"  FAIL: Missing {description}")
                all_passed = False

        if "raise BookNotFound" in output:
            print("  FAIL: Still contains raise statement")
            all_passed = False
        else:
            print("  PASS: raise statement removed")

        if all_passed:
            print("Self-test passed.")
            print(f"Output:\n{output}")
        else:
            print("Self-test FAILED.")
            print(f"Output:\n{output}")
            sys.exit(1)

    finally:
        os.unlink(temp_path)


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "--self-test":
        run_self_test()
        return

    if len(sys.argv) < 3:
        print("Usage: python result_type_rewrite.py <file.py> <function_name>")
        print("       python result_type_rewrite.py --self-test")
        sys.exit(1)

    filepath = sys.argv[1]
    function_name = sys.argv[2]

    if not os.path.exists(filepath):
        print(f"ERROR: File not found: {filepath}")
        sys.exit(1)

    rewritten = rewrite_file(filepath, function_name)
    if rewritten:
        print(f"OK: Rewrote method '{function_name}' to return Result[T, BookshelfError]")
    else:
        print(f"ERROR: Method '{function_name}' not found or already returns Result")
        sys.exit(1)


if __name__ == "__main__":
    main()
