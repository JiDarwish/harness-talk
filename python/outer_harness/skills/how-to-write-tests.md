# Skill: How to Write Tests

This skill describes the testing conventions for the bookshelf project. Follow these rules when writing any test.

## Use conftest.py fixtures for all test data

Every test object must come from the pytest fixtures in `bookshelf/tests/conftest.py`. Never create domain objects inline in tests.

### Available fixtures

| Fixture | Returns | Description |
|---------|---------|-------------|
| `book_repo` | `BookRepository` | Fresh in-memory book repository |
| `member_repo` | `MemberRepository` | Fresh in-memory member repository |
| `loan_repo` | `LoanRepository` | Fresh in-memory loan repository |
| `book_service` | `BookService` | Service wired to book_repo |
| `loan_service` | `LoanService` | Service wired to all three repos |
| `member_service` | `MemberService` | Service wired to member_repo |
| `a_book` | `Book` | Pre-saved book: "The Great Gatsby" by F. Scott Fitzgerald |
| `a_member` | `Member` | Pre-saved member: "Alice Johnson" |
| `client` | `TestClient` | FastAPI TestClient wired to all routes |

### Adding new fixtures

If you need test data that does not exist, add a new fixture to `conftest.py`:

```python
@pytest.fixture
def a_borrowed_book(book_repo, a_book):
    return book_repo.save(a_book.model_copy(update={"available": False}))
```

Never create inline test objects. Even for a one-off test, add a fixture.

## Test structure

### Service tests

Test service methods directly using fixtures. Group tests in a class:

```python
class TestMyService:
    def test_method_name__scenario__expected_outcome(self, my_service, a_book):
        # arrange -- fixtures handle setup

        # act
        result = my_service.do_something(a_book.id)

        # assert
        assert isinstance(result, Success)
        assert result.value.title == "The Great Gatsby"
```

Each test gets fresh repository instances because pytest fixtures are function-scoped by default.

### API tests

Use the `client` fixture (FastAPI `TestClient`):

```python
class TestMyRoutes:
    def test_endpoint__scenario__expected_status(self, client, a_book):
        response = client.get(f"/api/books/{a_book.id}")

        assert response.status_code == 200
        assert response.json()["title"] == "The Great Gatsby"
```

## Assert conventions

Use plain `assert` statements with `isinstance` for Result type checks:

```python
# Correct -- assert success
assert isinstance(result, Success)
assert result.value.title == "Clean Code"

# Correct -- assert specific failure
assert isinstance(result, Failure)
assert isinstance(result.error, BookNotFound)
assert result.error.id == 999

# Wrong -- using match/case in test assertions
match result:
    case Success(value):
        assert value.title == "Clean Code"
    case _:
        pytest.fail("Expected Success")
```

## Testing Result types

Service methods return `Result[T, BookshelfError]`. Assert on the type, then access the inner value:

```python
result = book_service.find_book(book_id)

# Assert success
assert isinstance(result, Success)
book = result.value
assert book.title == "The Great Gatsby"

# Assert specific failure
assert isinstance(result, Failure)
assert isinstance(result.error, BookNotFound)
```

## Test naming

Use the pattern: `test_method_name__scenario__expected_outcome`

Examples:
- `test_find_book__existing_book__returns_success`
- `test_borrow_book__unavailable_book__returns_book_not_available`
- `test_return_book__already_returned__returns_book_already_returned`
