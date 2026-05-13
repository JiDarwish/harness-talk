package com.example.bookshelf.service;

import com.example.bookshelf.domain.Book;
import com.example.bookshelf.domain.BookshelfError;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Member;
import com.example.bookshelf.domain.Result;
import com.example.bookshelf.persistence.BookRepository;
import com.example.bookshelf.persistence.LoanRepository;
import com.example.bookshelf.persistence.MemberRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class BookService {
    private final BookRepository bookRepository;
    private final MemberRepository memberRepository;
    private final LoanRepository loanRepository;

    public BookService(BookRepository bookRepository,
                       MemberRepository memberRepository,
                       LoanRepository loanRepository) {
        this.bookRepository = bookRepository;
        this.memberRepository = memberRepository;
        this.loanRepository = loanRepository;
    }

    public Result<Book, BookshelfError> addBook(String title, String author, String isbn) {
        var book = new Book(title, author, isbn);
        return Result.success(bookRepository.save(book));
    }

    public Result<Book, BookshelfError> findBook(Long id) {
        return bookRepository.findById(id)
                .<Result<Book, BookshelfError>>map(Result::success)
                .orElse(Result.failure(new BookshelfError.BookNotFound(id)));
    }

    public Result<List<Book>, BookshelfError> findAllBooks() {
        return Result.success(bookRepository.findAll());
    }

    // BUG: does not check book.isAvailable() before creating the loan.
    // This allows a book to be borrowed even when it's already on loan.
    public Result<Loan, BookshelfError> borrowBook(Long bookId, Long memberId) {
        var bookOpt = bookRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            return Result.failure(new BookshelfError.BookNotFound(bookId));
        }

        var memberOpt = memberRepository.findById(memberId);
        if (memberOpt.isEmpty()) {
            return Result.failure(new BookshelfError.MemberNotFound(memberId));
        }

        var book = bookOpt.get();
        // Missing: if (!book.isAvailable()) return Result.failure(new BookshelfError.BookNotAvailable(bookId));
        book.setAvailable(false);
        bookRepository.save(book);

        var loan = new Loan(book, memberOpt.get(), LocalDate.now(), LocalDate.now().plusWeeks(2));
        return Result.success(loanRepository.save(loan));
    }

    // Deliberately unconverted for Step 6 codemod demo
    public Loan findActiveLoan(Long loanId) {
        var loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new RuntimeException("Loan not found: " + loanId));
        if (loan.getReturnedAt() != null) {
            throw new RuntimeException("Loan already returned: " + loanId);
        }
        return loan;
    }
}
