# Bookshelf -- Agent Guide

This is a FastAPI service for a small library. Books and Members can be added; books can be borrowed and returned via loans.

## Package layout

| Package | Purpose | Key classes |
|---------|---------|-------------|
| `bookshelf.api` | FastAPI route handlers | `book_routes`, `member_routes`, `loan_routes` |
| `bookshelf.domain` | Dataclasses, value objects, errors | `Book`, `Member`, `Loan`, `BookshelfError`, `Result` |
| `bookshelf.service` | Business logic | `BookService`, `LoanService`, `MemberService` |
| `bookshelf.persistence` | In-memory repositories | `BookRepository`, `MemberRepository`, `LoanRepository` |
| `bookshelf.config` | Configuration | `Settings`, `FeatureFlags` |

**Important:** Repositories go in `persistence`, NOT `repository`. This is a team convention enforced by the import checker.

## Convention 1: Result types -- never raise from service methods

All service-layer methods return `Result[T, BookshelfError]` instead of raising exceptions. `Result` is a type alias:

```python
@dataclass(frozen=True)
class Success(Generic[T]):
    value: T

@dataclass(frozen=True)
class Failure(Generic[E]):
    error: E

type Result[T, E] = Success[T] | Failure[E]
```

`BookshelfError` is also a type union:

```python
type BookshelfError = (
    BookNotFound | MemberNotFound | BookNotAvailable | LoanNotFound | BookAlreadyReturned
)
```

**Correct -- return Result:**
```python
def find_book(self, book_id: int) -> Result[Book, BookshelfError]:
    book = self._book_repository.find_by_id(book_id)
    if book is None:
        return failure(BookNotFound(id=book_id))
    return success(book)
```

**Wrong -- raising exceptions:**
```python
def find_book(self, book_id: int) -> Book:
    book = self._book_repository.find_by_id(book_id)
    if book is None:
        raise BookNotFoundException(book_id)
    return book
```

Route handlers use `match`/`case` on `Result` to produce HTTP responses:

```python
result = book_service.find_book(book_id)
match result:
    case Success(value):
        return value
    case Failure(error):
        raise HTTPException(status_code=404, detail=str(error))
```

## Convention 2: Repositories in `persistence` package

All repository classes must be in `bookshelf.persistence`. Never create a `repository` package.

This is enforced by the import checker. If you put a repository in the wrong package, the check will fail with a clear error message.

## Convention 3: Test fixtures -- use conftest.py fixtures, no inline data

All tests must use the pytest fixtures from `conftest.py` for test data. Never create test objects inline.

**Correct:**
```python
def test_find_book(self, book_service, a_book):
    result = book_service.find_book(a_book.id)
    assert isinstance(result, Success)
```

**Wrong:**
```python
def test_find_book(self, book_service, book_repo):
    book = book_repo.save(Book(title="Some Title", author="Some Author", isbn="123-456"))
    result = book_service.find_book(book.id)
```

If you need a new fixture variant, add a fixture function to `conftest.py` rather than inlining data.

For detailed testing conventions, invoke the `/how-to-write-tests` skill.

## Convention 4: Feature flags via Pydantic BaseSettings, not ad-hoc env reads

Feature flags are defined in `config/feature_flags.py` using Pydantic `BaseSettings`:

```python
class FeatureFlags(BaseSettings):
    loan_extension_enabled: bool = False

    model_config = {"env_prefix": "FEATURES_"}
```

**Never use `os.environ` or `os.getenv`** for feature flags. All flags must go through the `FeatureFlags` class.

## How to add a new feature

1. Define the domain types (dataclasses, error cases) in `domain/`
2. Implement the business logic in `service/` -- return `Result[T, BookshelfError]`
3. Add the REST endpoint in `api/` -- use `match`/`case` on `Result`
4. Add repository methods in `persistence/` if needed
5. Add fixtures to `conftest.py`
6. Write tests using fixtures -- invoke `/how-to-write-tests` for conventions

## How to write tests

Invoke the `/how-to-write-tests` skill for the full testing guide.

Short version: use `conftest.py` fixtures, use `isinstance` assertions for Result types, use `TestClient` for API tests.

## Known issues

- `BookService.borrow_book()` has a known bug -- it does not check `book.available` before setting it False. There is a `@pytest.mark.skip` test for this in `test_loan_service.py`.
