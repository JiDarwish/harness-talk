///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.github.javaparser:javaparser-core:3.26.4

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

void main(String[] args) throws IOException {
    if (args.length == 0 || args[0].equals("--self-test")) {
        runSelfTest();
        return;
    }

    if (args.length < 2) {
        IO.println("Usage: result-type-rewrite.java <file> <methodName>");
        IO.println("       result-type-rewrite.java --self-test");
        System.exit(1);
    }

    var filePath = Path.of(args[0]);
    var methodName = args[1];

    if (!Files.exists(filePath)) {
        IO.println("ERROR: File not found: " + filePath);
        System.exit(1);
    }

    var cu = StaticJavaParser.parse(filePath);
    var rewritten = rewriteMethod(cu, methodName);

    if (rewritten) {
        Files.writeString(filePath, cu.toString());
        IO.println("OK: Rewrote method '" + methodName + "' to return Result<T, BookshelfError>");
    } else {
        IO.println("ERROR: Method '" + methodName + "' not found or already returns Result");
        System.exit(1);
    }
}

boolean rewriteMethod(CompilationUnit cu, String methodName) {
    var methods = cu.findAll(MethodDeclaration.class, m -> m.getNameAsString().equals(methodName));
    if (methods.isEmpty()) return false;

    var method = methods.getFirst();
    var originalReturnType = method.getType().asString();

    if (originalReturnType.startsWith("Result")) return false;

    var resultType = new ClassOrInterfaceType(null, "Result");
    resultType.setTypeArguments(
            new ClassOrInterfaceType(null, originalReturnType),
            new ClassOrInterfaceType(null, "BookshelfError"));
    method.setType(resultType);

    method.getThrownExceptions().clear();

    method.walk(ReturnStmt.class, returnStmt -> {
        returnStmt.getExpression().ifPresent(expr -> {
            var wrapped = new MethodCallExpr(new NameExpr("Result"), "success", new NodeList<>(expr.clone()));
            returnStmt.setExpression(wrapped);
        });
    });

    method.walk(ThrowStmt.class, throwStmt -> {
        var throwExpr = throwStmt.getExpression();
        var failureCall = new MethodCallExpr(new NameExpr("Result"), "failure", new NodeList<>(throwExpr.clone()));
        var returnStmt = new ReturnStmt(failureCall);
        throwStmt.replace(returnStmt);
    });

    method.walk(TryStmt.class, tryStmt -> {
        for (var catchClause : tryStmt.getCatchClauses()) {
            catchClause.getBody().walk(ThrowStmt.class, throwStmt -> {
                var failureCall = new MethodCallExpr(
                        new NameExpr("Result"), "failure",
                        new NodeList<>(throwStmt.getExpression().clone()));
                throwStmt.replace(new ReturnStmt(failureCall));
            });
        }
    });

    return true;
}

void runSelfTest() {
    IO.println("Running self-test...");

    var input = """
            package test;
            class Example {
                Book findBook(Long id) throws BookNotFoundException {
                    var book = repository.findById(id);
                    if (book == null) {
                        throw new BookNotFoundException(id);
                    }
                    return book;
                }
            }
            """;

    try {
        var cu = StaticJavaParser.parse(input);
        var rewritten = rewriteMethod(cu, "findBook");

        if (!rewritten) {
            IO.println("FAIL: Self-test could not rewrite method");
            System.exit(1);
        }

        var output = cu.toString();
        if (output.contains("Result<Book, BookshelfError>")
                && output.contains("Result.success")
                && output.contains("Result.failure")
                && !output.contains("throws BookNotFoundException")) {
            IO.println("PASS: Self-test successfully rewrote method");
            IO.println("Output:\n" + output);
        } else {
            IO.println("FAIL: Rewritten output missing expected patterns");
            IO.println("Output:\n" + output);
            System.exit(1);
        }
    } catch (Exception e) {
        IO.println("FAIL: Self-test threw exception: " + e.getMessage());
        System.exit(1);
    }
}
