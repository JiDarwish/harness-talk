# Bookshelf — Agent Guide

This is a Spring Boot service for a small library. Books and Members can be added; books can be borrowed and returned via loans.

## Package layout

| Package | Purpose | Key classes |
|---------|---------|-------------|
| `com.example.bookshelf.api` | REST controllers | `BookController`, `MemberController`, `LoanController` |
| `com.example.bookshelf.domain` | Entities, value objects, errors | `Book`, `Member`, `Loan`, `BookshelfError`, `Result` |
| `com.example.bookshelf.service` | Business logic | `BookService`, `LoanService` |
| `com.example.bookshelf.persistence` | Spring Data repositories | `BookRepository`, `MemberRepository`, `LoanRepository` |
| `com.example.bookshelf.config` | Configuration | `BookshelfConfig`, `FeatureFlags` |

**Important:** Repositories go in `persistence`, NOT `repository`. This is a team convention enforced by ArchUnit.

## Convention 1: Result types — never throw from service methods

All service-layer methods return `Result<T, BookshelfError>` instead of throwing exceptions. `Result` is a sealed interface:

```java
public sealed interface Result<T, E> {
    record Success<T, E>(T value) implements Result<T, E> {}
    record Failure<T, E>(E error) implements Result<T, E> {}
}
```

`BookshelfError` is also sealed:

```java
public sealed interface BookshelfError {
    record BookNotFound(Long id) implements BookshelfError {}
    record MemberNotFound(Long id) implements BookshelfError {}
    record BookNotAvailable(Long bookId) implements BookshelfError {}
    record LoanNotFound(Long id) implements BookshelfError {}
    record BookAlreadyReturned(Long loanId) implements BookshelfError {}
}
```

**Correct — return Result:**
```java
public Result<Book, BookshelfError> findBook(Long id) {
    return bookRepository.findById(id)
            .<Result<Book, BookshelfError>>map(Result::success)
            .orElse(Result.failure(new BookshelfError.BookNotFound(id)));
}
```

**Wrong — throwing exceptions:**
```java
public Book findBook(Long id) {
    return bookRepository.findById(id)
            .orElseThrow(() -> new BookNotFoundException(id));
}
```

Controllers pattern-match on `Result` to produce HTTP responses:

```java
return switch (bookService.findBook(id)) {
    case Result.Success(var book) -> ResponseEntity.ok(book);
    case Result.Failure(var error) -> ResponseEntity.notFound().build();
};
```

## Convention 2: Repositories in `persistence` package

All Spring Data repository interfaces must be in `com.example.bookshelf.persistence`. Never create a `repository` package.

This is enforced by an ArchUnit test. If you put a repository in the wrong package, the build will fail with a clear error message.

## Convention 3: Test fixtures — use BookshelfFixtures, no inline data

All tests must use the `BookshelfFixtures` class for test data. Never create test objects inline.

**Correct:**
```java
var book = bookRepository.save(BookshelfFixtures.aBook());
var member = memberRepository.save(BookshelfFixtures.aMember());
```

**Wrong:**
```java
var book = bookRepository.save(new Book("Some Title", "Some Author", "123-456"));
var member = memberRepository.save(new Member("Test User", "test@test.com"));
```

If you need a new fixture variant, add a static method to `BookshelfFixtures` rather than inlining data.

For detailed testing conventions, invoke the `/how-to-write-tests` skill.

## Convention 4: Feature flags via YAML, not annotations

Feature flags are defined in `application.yml` under the `features` prefix and read via the `FeatureFlags` bean:

```yaml
features:
  loan-extension-enabled: false
```

```java
@Autowired FeatureFlags featureFlags;

if (featureFlags.isLoanExtensionEnabled()) { ... }
```

**Never use `@ConditionalOnProperty`** for feature flags. All flags must go through the `FeatureFlags` bean.

## How to add a new feature

1. Define the domain types (entities, error cases) in `domain/`
2. Implement the business logic in `service/` — return `Result<T, BookshelfError>`
3. Add the REST endpoint in `api/` — pattern-match on `Result`
4. Add repository methods in `persistence/` if needed
5. Add fixtures to `BookshelfFixtures`
6. Write tests using fixtures — invoke `/how-to-write-tests` for conventions

## How to write tests

Invoke the `/how-to-write-tests` skill for the full testing guide.

Short version: use `BookshelfFixtures`, use AssertJ assertions, use `@SpringBootTest` for service tests and `@SpringBootTest` + `@AutoConfigureMockMvc` for controller tests.

## Known issues

- `BookService.borrowBook()` has a known bug — it does not check `book.isAvailable()` before creating a loan. There is a `@Disabled` test for this in `BookServiceTest`.
