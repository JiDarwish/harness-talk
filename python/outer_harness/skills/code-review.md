# Skill: Code Review

You are reviewing a diff against the bookshelf codebase. Check for the following issues, in priority order. Report each finding with a severity level.

## What to check

### CRITICAL -- Convention violations

1. **Result type convention**: Service methods must return `Result[T, BookshelfError]`, never raise exceptions. Check for `raise` statements inside service methods, or methods returning raw types instead of `Result`.

2. **Package placement**: Repositories in `persistence/`, services in `service/`, routes in `api/`, models and errors in `domain/`. Flag any class in the wrong directory.

3. **Feature flags**: Must use the `FeatureFlags` class from `config/feature_flags.py` (Pydantic `BaseSettings`), never `os.environ` or `os.getenv`.

4. **Test fixtures**: All test data must come from `conftest.py` fixtures. Flag any inline `Book(...)`, `Member(...)`, or `Loan(...)` construction in test files.

### HIGH -- Semantic duplicates

5. **Duplicate test fixtures**: Check if a new fixture duplicates an existing one. Compare the data values, not just the fixture names.

6. **Duplicate test coverage**: Check if a new test covers the same scenario as an existing test, even if named differently.

7. **Duplicate business logic**: Check if new service methods replicate logic that already exists elsewhere.

### MEDIUM -- Design issues

8. **Missed edge cases**: Are there error conditions the code does not handle? For example: None inputs, empty collections, concurrent modifications.

9. **Over-engineering**: Is the code more complex than necessary? Extra abstraction layers, unnecessary base classes, premature generalization.

10. **Naming**: Do names follow existing patterns? Routes in `*_routes.py`, services as `*Service`, repositories as `*Repository`.

## Output format

For each finding, report:

```
[SEVERITY] file:line -- description
  WHY: explanation of why this is a problem
  FIX: specific suggestion for how to fix it
```

If no issues found, report: "No issues found. Code follows all conventions."
