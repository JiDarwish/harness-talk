# Skill: Architecture Review

You are reviewing a diff for architectural fitness. Check the following structural properties of the bookshelf codebase.

## What to check

### Package boundaries

- All route handlers are in `bookshelf/api/`
- All service classes are in `bookshelf/service/`
- All repository classes are in `bookshelf/persistence/`
- All domain models and errors are in `bookshelf/domain/`
- All configuration is in `bookshelf/config/`

### Dependency direction

- `api` depends on `service` and `domain` (never on `persistence` directly)
- `service` depends on `persistence` and `domain` (never on `api`)
- `persistence` depends on `domain` only (never on `service` or `api`)
- `domain` has no internal dependencies on other app layers (persistence, service, api)
- `config` may depend on any package

### Naming conventions

- Route modules: `*_routes.py`
- Services: `*Service`
- Repositories: `*Repository`
- Models: singular nouns (`Book`, not `Books`)
- Error types: standalone dataclasses in `errors.py` (`BookNotFound`, `MemberNotFound`)

### API design

- REST endpoints follow `/api/{resource}` pattern
- POST for creation, GET for retrieval
- Consistent use of `match`/`case` on Result in route handlers
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
[SEVERITY] file:line -- description
  RULE: which architectural rule is violated
  FIX: how to fix it
```
