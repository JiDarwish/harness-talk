package com.example.bookshelf.domain;

public sealed interface BookshelfError {
    record BookNotFound(Long id) implements BookshelfError {}
    record MemberNotFound(Long id) implements BookshelfError {}
    record BookNotAvailable(Long bookId) implements BookshelfError {}
    record LoanNotFound(Long id) implements BookshelfError {}
    record BookAlreadyReturned(Long loanId) implements BookshelfError {}
}
