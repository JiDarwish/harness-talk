package com.example.bookshelf.service;

import com.example.bookshelf.BookshelfFixtures;
import com.example.bookshelf.domain.BookshelfError;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Result;
import com.example.bookshelf.persistence.BookRepository;
import com.example.bookshelf.persistence.LoanRepository;
import com.example.bookshelf.persistence.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoanServiceTest {

    @Autowired LoanService loanService;
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
    void returnBook_activeLoan_marksReturned() {
        var book = bookRepository.save(BookshelfFixtures.aBorrowedBook());
        var member = memberRepository.save(BookshelfFixtures.aMember());
        var loan = loanRepository.save(BookshelfFixtures.aLoan(book, member));

        var result = loanService.returnBook(loan.getId());

        assertThat(result).isInstanceOf(Result.Success.class);
        var returned = ((Result.Success<Loan, BookshelfError>) result).value();
        assertThat(returned.getReturnedAt()).isNotNull();

        var updatedBook = bookRepository.findById(book.getId()).orElseThrow();
        assertThat(updatedBook.isAvailable()).isTrue();
    }

    @Test
    void returnBook_alreadyReturned_returnsBookAlreadyReturned() {
        var book = bookRepository.save(BookshelfFixtures.aBorrowedBook());
        var member = memberRepository.save(BookshelfFixtures.aMember());
        var loan = loanRepository.save(BookshelfFixtures.aLoan(book, member));
        loanService.returnBook(loan.getId());

        var result = loanService.returnBook(loan.getId());

        assertThat(result).isInstanceOf(Result.Failure.class);
        var error = ((Result.Failure<Loan, BookshelfError>) result).error();
        assertThat(error).isInstanceOf(BookshelfError.BookAlreadyReturned.class);
    }

    @Test
    void returnBook_nonExistentLoan_returnsLoanNotFound() {
        var result = loanService.returnBook(999L);

        assertThat(result).isInstanceOf(Result.Failure.class);
        var error = ((Result.Failure<Loan, BookshelfError>) result).error();
        assertThat(error).isInstanceOf(BookshelfError.LoanNotFound.class);
    }

    @Test
    void findLoan_existingLoan_returnsSuccess() {
        var book = bookRepository.save(BookshelfFixtures.aBook());
        var member = memberRepository.save(BookshelfFixtures.aMember());
        var loan = loanRepository.save(BookshelfFixtures.aLoan(book, member));

        var result = loanService.findLoan(loan.getId());

        assertThat(result).isInstanceOf(Result.Success.class);
    }

    @Test
    void findLoan_nonExistent_returnsLoanNotFound() {
        var result = loanService.findLoan(999L);

        assertThat(result).isInstanceOf(Result.Failure.class);
    }

    @Test
    void findLoansByMember_returnsMatchingLoans() {
        var book = bookRepository.save(BookshelfFixtures.aBook());
        var member = memberRepository.save(BookshelfFixtures.aMemberWithLoans());
        loanRepository.save(BookshelfFixtures.aLoan(book, member));

        var result = loanService.findLoansByMember(member.getId());

        assertThat(result).isInstanceOf(Result.Success.class);
        var loans = ((Result.Success<java.util.List<Loan>, BookshelfError>) result).value();
        assertThat(loans).hasSize(1);
    }
}
