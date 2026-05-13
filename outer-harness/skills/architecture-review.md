# Skill: Architecture Review

You are reviewing a diff for architectural fitness. Check the following structural properties of the bookshelf codebase.

## What to check

### Package boundaries

- All `@RestController` classes are in `com.example.bookshelf.api`
- All `@Service` classes are in `com.example.bookshelf.service`
- All `JpaRepository` interfaces are in `com.example.bookshelf.persistence`
- All entities and value objects are in `com.example.bookshelf.domain`
- All configuration is in `com.example.bookshelf.config`

### Dependency direction

- `api` depends on `service` and `domain` (never on `persistence` directly)
- `service` depends on `persistence` and `domain` (never on `api`)
- `persistence` depends on `domain` only (never on `service` or `api`)
- `domain` has no internal dependencies on other packages
- `config` may depend on any package

### Naming conventions

- Controllers: `XxxController`
- Services: `XxxService`
- Repositories: `XxxRepository`
- Entities: singular nouns (`Book`, not `Books`)
- Error types: inner records of `BookshelfError`

### API design

- REST endpoints follow `/api/{resource}` pattern
- POST for creation, GET for retrieval
- Consistent use of `Result` pattern matching in controllers
- Proper HTTP status codes (200, 404, 409)

## What this review does NOT check

This review checks structural fitness only. It does NOT verify:
- Whether the code is **functionally correct** (does the business logic do the right thing?)
- Whether edge cases in business logic are handled
- Whether the feature matches the requirement

Functional correctness requires human review or dedicated behavioral tests.

## Output format

For each finding:

```
[SEVERITY] file:line — description
  RULE: which architectural rule is violated
  FIX: how to fix it
```
