package com.example.bookshelf.persistence;

import com.example.bookshelf.domain.Loan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    List<Loan> findByMemberId(Long memberId);
    List<Loan> findByBookIdAndReturnedAtIsNull(Long bookId);
}
