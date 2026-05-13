# Skill: How to Write Tests

This skill describes the testing conventions for the bookshelf project. Follow these rules when writing any test.

## Use BookshelfFixtures for all test data

Every test object must come from `com.example.bookshelf.BookshelfFixtures`. Never create domain objects inline in tests.

### Available fixtures

| Method | Returns | Description |
|--------|---------|-------------|
| `aBook()` | `Book` | Available book: "Clean Code" by Robert C. Martin |
| `anotherBook()` | `Book` | Available book: "Refactoring" by Martin Fowler |
| `aBorrowedBook()` | `Book` | Unavailable book: "Effective Java" by Joshua Bloch |
| `aMember()` | `Member` | "Alice Smith" |
| `anotherMember()` | `Member` | "Bob Jones" |
| `aMemberWithLoans()` | `Member` | "Carol White" — use when testing loan scenarios |
| `aLoan(Book, Member)` | `Loan` | Active loan, due in 2 weeks |
| `anOverdueLoan(Book, Member)` | `Loan` | Overdue loan, was due 2 weeks ago |

### Adding new fixtures

If you need test data that doesn't exist, add a new static method to `BookshelfFixtures`:

```java
public static Book aBookWithLongTitle() {
    return new Book("A Very Long Title That Tests Our Display Logic", "Author Name", "978-0000000000");
}
```

Never create inline test objects. Even for a one-off test, add a fixture.

## Test structure

### Service tests

Use `@SpringBootTest` with real H2 database. Clean up in `@BeforeEach`:

```java
@SpringBootTest
class MyServiceTest {

    @Autowired MyService myService;
    @Autowired BookRepository bookRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired LoanRepository loanRepository;

    @BeforeEach
    void setUp() {
        loanRepository.deleteAll();
        bookRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    void methodName_scenario_expectedOutcome() {
        // arrange — use fixtures
        var book = bookRepository.save(BookshelfFixtures.aBook());

        // act
        var result = myService.doSomething(book.getId());

        // assert — use AssertJ
        assertThat(result).isInstanceOf(Result.Success.class);
    }
}
```

Delete order matters: loans first (has FK to books and members), then books, then members.

### Controller tests

Use `@SpringBootTest` + `@AutoConfigureMockMvc`:

```java
@SpringBootTest
@AutoConfigureMockMvc
class MyControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void endpoint_scenario_expectedStatus() throws Exception {
        mockMvc.perform(get("/api/endpoint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.field").value("expected"));
    }
}
```

## AssertJ conventions

Use AssertJ for all assertions. Never use JUnit's `assertEquals` or `assertTrue`.

```java
// Correct
assertThat(result).isInstanceOf(Result.Success.class);
assertThat(book.getTitle()).isEqualTo("Clean Code");
assertThat(books).hasSize(2);
assertThat(book.isAvailable()).isTrue();

// Wrong
assertEquals("Clean Code", book.getTitle());
assertTrue(book.isAvailable());
```

## Testing Result types

Service methods return `Result<T, BookshelfError>`. Assert on the type, then extract the value:

```java
var result = bookService.findBook(id);

// Assert success
assertThat(result).isInstanceOf(Result.Success.class);
var book = ((Result.Success<Book, BookshelfError>) result).value();
assertThat(book.getTitle()).isEqualTo("Clean Code");

// Assert specific failure
assertThat(result).isInstanceOf(Result.Failure.class);
var error = ((Result.Failure<Book, BookshelfError>) result).error();
assertThat(error).isInstanceOf(BookshelfError.BookNotFound.class);
```

## Test naming

Use the pattern: `methodName_scenario_expectedOutcome`

Examples:
- `findBook_existingBook_returnsSuccess`
- `borrowBook_unavailableBook_returnsBookNotAvailable`
- `returnBook_alreadyReturned_returnsBookAlreadyReturned`
