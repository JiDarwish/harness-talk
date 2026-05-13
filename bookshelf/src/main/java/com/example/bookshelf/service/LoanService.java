package com.example.bookshelf.service;

import com.example.bookshelf.domain.BookshelfError;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Result;
import com.example.bookshelf.persistence.BookRepository;
import com.example.bookshelf.persistence.LoanRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class LoanService {
    private final LoanRepository loanRepository;
    private final BookRepository bookRepository;

    public LoanService(LoanRepository loanRepository, BookRepository bookRepository) {
        this.loanRepository = loanRepository;
        this.bookRepository = bookRepository;
    }

    public Result<Loan, BookshelfError> returnBook(Long loanId) {
        var loanOpt = loanRepository.findById(loanId);
        if (loanOpt.isEmpty()) {
            return Result.failure(new BookshelfError.LoanNotFound(loanId));
        }

        var loan = loanOpt.get();
        if (loan.getReturnedAt() != null) {
            return Result.failure(new BookshelfError.BookAlreadyReturned(loanId));
        }

        loan.setReturnedAt(LocalDate.now());
        var book = loan.getBook();
        book.setAvailable(true);
        bookRepository.save(book);
        return Result.success(loanRepository.save(loan));
    }

    public Result<Loan, BookshelfError> findLoan(Long id) {
        return loanRepository.findById(id)
                .<Result<Loan, BookshelfError>>map(Result::success)
                .orElse(Result.failure(new BookshelfError.LoanNotFound(id)));
    }

    public Result<List<Loan>, BookshelfError> findLoansByMember(Long memberId) {
        return Result.success(loanRepository.findByMemberId(memberId));
    }
}
