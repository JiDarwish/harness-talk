package com.example.bookshelf.api;

import com.example.bookshelf.domain.Book;
import com.example.bookshelf.domain.BookshelfError;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Result;
import com.example.bookshelf.service.BookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    record AddBookRequest(String title, String author, String isbn) {}

    @PostMapping
    public ResponseEntity<Book> addBook(@RequestBody AddBookRequest request) {
        return switch (bookService.addBook(request.title(), request.author(), request.isbn())) {
            case Result.Success(var book) -> ResponseEntity.ok(book);
            case Result.Failure(var error) -> ResponseEntity.internalServerError().build();
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<Book> findBook(@PathVariable Long id) {
        return switch (bookService.findBook(id)) {
            case Result.Success(var book) -> ResponseEntity.ok(book);
            case Result.Failure(var error) -> ResponseEntity.notFound().build();
        };
    }

    @GetMapping
    public ResponseEntity<List<Book>> findAllBooks() {
        return switch (bookService.findAllBooks()) {
            case Result.Success(var books) -> ResponseEntity.ok(books);
            case Result.Failure(var error) -> ResponseEntity.internalServerError().build();
        };
    }

    @PostMapping("/{bookId}/borrow")
    public ResponseEntity<?> borrowBook(@PathVariable Long bookId, @RequestParam Long memberId) {
        return switch (bookService.borrowBook(bookId, memberId)) {
            case Result.Success(var loan) -> ResponseEntity.ok(loan);
            case Result.Failure(var error) -> switch (error) {
                case BookshelfError.BookNotFound e -> ResponseEntity.notFound().build();
                case BookshelfError.MemberNotFound e -> ResponseEntity.notFound().build();
                case BookshelfError.BookNotAvailable e -> ResponseEntity.status(409).build();
                default -> ResponseEntity.internalServerError().build();
            };
        };
    }
}
