package com.example.bookshelf.api;

import com.example.bookshelf.domain.BookshelfError;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Result;
import com.example.bookshelf.service.LoanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/loans")
public class LoanController {
    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    @PostMapping("/{loanId}/return")
    public ResponseEntity<?> returnBook(@PathVariable Long loanId) {
        return switch (loanService.returnBook(loanId)) {
            case Result.Success(var loan) -> ResponseEntity.ok(loan);
            case Result.Failure(var error) -> switch (error) {
                case BookshelfError.LoanNotFound e -> ResponseEntity.notFound().build();
                case BookshelfError.BookAlreadyReturned e -> ResponseEntity.status(409).build();
                default -> ResponseEntity.internalServerError().build();
            };
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<Loan> findLoan(@PathVariable Long id) {
        return switch (loanService.findLoan(id)) {
            case Result.Success(var loan) -> ResponseEntity.ok(loan);
            case Result.Failure(var error) -> ResponseEntity.notFound().build();
        };
    }

    @GetMapping("/member/{memberId}")
    public ResponseEntity<List<Loan>> findLoansByMember(@PathVariable Long memberId) {
        return switch (loanService.findLoansByMember(memberId)) {
            case Result.Success(var loans) -> ResponseEntity.ok(loans);
            case Result.Failure(var error) -> ResponseEntity.internalServerError().build();
        };
    }
}
