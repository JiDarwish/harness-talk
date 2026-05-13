///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.github.javaparser:javaparser-core:3.26.4

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

void main(String[] args) throws IOException {
    if (args.length == 0 || args[0].equals("--self-test")) {
        runSelfTest();
        return;
    }

    var path = Path.of(args[0]);
    if (!Files.exists(path)) {
        IO.println("ERROR: File not found: " + path);
        System.exit(1);
    }

    var findings = check(path);
    if (findings.isEmpty()) {
        IO.println("OK: No Result unwrap issues found in " + path);
    } else {
        for (var finding : findings) {
            IO.println(finding);
        }
        System.exit(1);
    }
}

List<String> check(Path file) throws IOException {
    var cu = StaticJavaParser.parse(file);
    var findings = new ArrayList<String>();
    var checker = new ResultUnwrapVisitor(file.toString(), findings);
    checker.visit(cu, null);
    return findings;
}

class ResultUnwrapVisitor extends VoidVisitorAdapter<Void> {
    private final String fileName;
    private final List<String> findings;

    ResultUnwrapVisitor(String fileName, List<String> findings) {
        this.fileName = fileName;
        this.findings = findings;
    }

    @Override
    public void visit(MethodCallExpr call, Void arg) {
        super.visit(call, arg);

        if (call.getNameAsString().equals("value") && call.getScope().isPresent()) {
            var scope = call.getScope().get().toString();
            if (scope.contains("result") || scope.contains("Result")) {
                int line = call.getBegin().map(p -> p.line).orElse(0);
                findings.add(String.format("""
                        ERROR at %s:%d — Unsafe Result narrowing to access .value().
                        WHY: Accessing .value() through a cast or non-exhaustive check bypasses \
                        the sealed interface's safety guarantee. If the Result is actually a Failure, \
                        this path throws at runtime.
                        FIX: Use pattern matching with switch instead:
                          return switch (result) {
                              case Result.Success(var value) -> // use value safely here
                              case Result.Failure(var error) -> // handle the error case
                          };""", fileName, line));
            }
        }
    }

    @Override
    public void visit(CastExpr cast, Void arg) {
        super.visit(cast, arg);

        var typeName = cast.getType().asString();
        if (typeName.contains("Result.Success") || typeName.contains("Result.Failure")) {
            int line = cast.getBegin().map(p -> p.line).orElse(0);
            findings.add(String.format("""
                    ERROR at %s:%d — Unsafe cast to %s.
                    WHY: Casting a Result to Success or Failure without exhaustive matching is \
                    fragile. If the Result is the other variant, this throws ClassCastException \
                    at runtime. The sealed interface exists to enable safe pattern matching.
                    FIX: Use pattern matching with switch instead of instanceof+cast:
                      return switch (result) {
                          case Result.Success(var value) -> // use value safely
                          case Result.Failure(var error) -> // handle error
                      };""", fileName, line, typeName));
        }
    }

    @Override
    public void visit(InstanceOfExpr expr, Void arg) {
        super.visit(expr, arg);

        var typeName = expr.getType().asString();
        if ((typeName.contains("Result.Success") || typeName.contains("Result.Failure"))
                && expr.getPattern().isEmpty()) {
            int line = expr.getBegin().map(p -> p.line).orElse(0);
            findings.add(String.format("""
                    ERROR at %s:%d — Using instanceof %s without pattern variable.
                    WHY: Plain instanceof checks on Result subtypes lead to unsafe casts on the \
                    next line. Java 25 supports pattern matching in instanceof, and the Result \
                    sealed interface supports exhaustive switch — both are safer alternatives.
                    FIX: Either use instanceof with a pattern variable:
                      if (result instanceof Result.Success(var value)) { ... }
                    Or preferably, use switch:
                      return switch (result) {
                          case Result.Success(var value) -> // use value
                          case Result.Failure(var error) -> // handle error
                      };""", fileName, line, typeName));
        }
    }
}

void runSelfTest() {
    IO.println("Running self-test...");

    var badCode = """
            package test;
            import com.example.bookshelf.domain.Result;
            import com.example.bookshelf.domain.BookshelfError;
            class Bad {
                void unsafeCast(Result<String, BookshelfError> result) {
                    if (result instanceof Result.Success) {
                        var s = (Result.Success<String, BookshelfError>) result;
                        System.out.println(s.value());
                    }
                }
                void unsafeDirectAccess(Result<String, BookshelfError> result) {
                    var value = result.value();
                }
            }
            """;

    try {
        var tempFile = Files.createTempFile("bad-result-usage", ".java");
        Files.writeString(tempFile, badCode);
        var findings = check(tempFile);
        Files.delete(tempFile);

        if (findings.isEmpty()) {
            IO.println("FAIL: Self-test found no issues in known-bad code!");
            System.exit(1);
        }

        IO.println("PASS: Self-test found " + findings.size() + " issues in known-bad code:");
        for (var finding : findings) {
            IO.println("  " + finding.lines().findFirst().orElse(""));
        }
    } catch (IOException e) {
        IO.println("FAIL: Self-test threw exception: " + e.getMessage());
        System.exit(1);
    }
}
