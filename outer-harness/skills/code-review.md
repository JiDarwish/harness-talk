# Skill: Code Review

You are reviewing a diff against the bookshelf codebase. Check for the following issues, in priority order. Report each finding with a severity level.

## What to check

### CRITICAL — Convention violations

1. **Result type convention**: Service methods must return `Result<T, BookshelfError>`, never throw exceptions. Check for `throws` clauses, `throw new` statements, or methods returning raw types instead of `Result`.

2. **Package placement**: Repositories in `persistence/`, services in `service/`, controllers in `api/`, entities in `domain/`. Flag any class in the wrong package.

3. **Feature flags**: Must use `FeatureFlags` bean from `application.yml`, never `@ConditionalOnProperty`.

4. **Test fixtures**: All test data must come from `BookshelfFixtures`. Flag any inline `new Book(...)`, `new Member(...)`, or `new Loan(...)` in test files.

### HIGH — Semantic duplicates

5. **Duplicate test fixtures**: Check if a new fixture method duplicates an existing one. Compare the data values, not just the method names.

6. **Duplicate test coverage**: Check if a new test covers the same scenario as an existing test, even if named differently.

7. **Duplicate business logic**: Check if new service methods replicate logic that already exists elsewhere.

### MEDIUM — Design issues

8. **Missed edge cases**: Are there error conditions the code doesn't handle? For example: null inputs, empty collections, concurrent modifications.

9. **Over-engineering**: Is the code more complex than necessary? Extra abstraction layers, unnecessary interfaces, premature generalization.

10. **Naming**: Do names follow existing patterns? Controllers end in `Controller`, services in `Service`, etc.

## Output format

For each finding, report:

```
[SEVERITY] file:line — description
  WHY: explanation of why this is a problem
  FIX: specific suggestion for how to fix it
```

If no issues found, report: "No issues found. Code follows all conventions."
