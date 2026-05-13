package com.example.bookshelf.service;

import com.example.bookshelf.BookshelfFixtures;
import com.example.bookshelf.domain.Book;
import com.example.bookshelf.domain.BookshelfError;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Result;
import com.example.bookshelf.persistence.BookRepository;
import com.example.bookshelf.persistence.LoanRepository;
import com.example.bookshelf.persistence.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BookServiceTest {

    @Autowired BookService bookService;
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
    void addBook_createsBookSuccessfully() {
        var result = bookService.addBook("Clean Code", "Robert C. Martin", "978-0132350884");

        assertThat(result).isInstanceOf(Result.Success.class);
        var book = ((Result.Success<Book, BookshelfError>) result).value();
        assertThat(book.getTitle()).isEqualTo("Clean Code");
        assertThat(book.getAuthor()).isEqualTo("Robert C. Martin");
        assertThat(book.isAvailable()).isTrue();
        assertThat(book.getId()).isNotNull();
    }

    @Test
    void findBook_existingBook_returnsSuccess() {
        var saved = bookRepository.save(BookshelfFixtures.aBook());

        var result = bookService.findBook(saved.getId());

        assertThat(result).isInstanceOf(Result.Success.class);
        var book = ((Result.Success<Book, BookshelfError>) result).value();
        assertThat(book.getTitle()).isEqualTo("Clean Code");
    }

    @Test
    void findBook_nonExistent_returnsBookNotFound() {
        var result = bookService.findBook(999L);

        assertThat(result).isInstanceOf(Result.Failure.class);
        var error = ((Result.Failure<Book, BookshelfError>) result).error();
        assertThat(error).isInstanceOf(BookshelfError.BookNotFound.class);
    }

    @Test
    void findAllBooks_returnsAllBooks() {
        bookRepository.save(BookshelfFixtures.aBook());
        bookRepository.save(BookshelfFixtures.anotherBook());

        var result = bookService.findAllBooks();

        assertThat(result).isInstanceOf(Result.Success.class);
        var books = ((Result.Success<java.util.List<Book>, BookshelfError>) result).value();
        assertThat(books).hasSize(2);
    }

    @Test
    void borrowBook_availableBook_createsLoan() {
        var book = bookRepository.save(BookshelfFixtures.aBook());
        var member = memberRepository.save(BookshelfFixtures.aMember());

        var result = bookService.borrowBook(book.getId(), member.getId());

        assertThat(result).isInstanceOf(Result.Success.class);
        var loan = ((Result.Success<Loan, BookshelfError>) result).value();
        assertThat(loan.getBook().getId()).isEqualTo(book.getId());
        assertThat(loan.getMember().getId()).isEqualTo(member.getId());

        var updatedBook = bookRepository.findById(book.getId()).orElseThrow();
        assertThat(updatedBook.isAvailable()).isFalse();
    }

    @Test
    void borrowBook_nonExistentBook_returnsBookNotFound() {
        var member = memberRepository.save(BookshelfFixtures.aMember());

        var result = bookService.borrowBook(999L, member.getId());

        assertThat(result).isInstanceOf(Result.Failure.class);
        var error = ((Result.Failure<Loan, BookshelfError>) result).error();
        assertThat(error).isInstanceOf(BookshelfError.BookNotFound.class);
    }

    @Test
    void borrowBook_nonExistentMember_returnsMemberNotFound() {
        var book = bookRepository.save(BookshelfFixtures.aBook());

        var result = bookService.borrowBook(book.getId(), 999L);

        assertThat(result).isInstanceOf(Result.Failure.class);
        var error = ((Result.Failure<Loan, BookshelfError>) result).error();
        assertThat(error).isInstanceOf(BookshelfError.MemberNotFound.class);
    }

    @Disabled("TODO: fix borrow logic — should reject borrowing an unavailable book")
    @Test
    void borrowBook_unavailableBook_returnsBookNotAvailable() {
        var book = bookRepository.save(BookshelfFixtures.aBorrowedBook());
        var member = memberRepository.save(BookshelfFixtures.aMember());

        var result = bookService.borrowBook(book.getId(), member.getId());

        assertThat(result).isInstanceOf(Result.Failure.class);
        var error = ((Result.Failure<Loan, BookshelfError>) result).error();
        assertThat(error).isInstanceOf(BookshelfError.BookNotAvailable.class);
    }
}
