package com.example.bookshelf;

import com.example.bookshelf.domain.Book;
import com.example.bookshelf.domain.Loan;
import com.example.bookshelf.domain.Member;

import java.time.LocalDate;

public final class BookshelfFixtures {

    private BookshelfFixtures() {}

    public static Book aBook() {
        return new Book("Clean Code", "Robert C. Martin", "978-0132350884");
    }

    public static Book anotherBook() {
        return new Book("Refactoring", "Martin Fowler", "978-0134757599");
    }

    public static Book aBorrowedBook() {
        var book = new Book("Effective Java", "Joshua Bloch", "978-0134685991");
        book.setAvailable(false);
        return book;
    }

    public static Member aMember() {
        return new Member("Alice Smith", "alice@example.com");
    }

    public static Member anotherMember() {
        return new Member("Bob Jones", "bob@example.com");
    }

    public static Member aMemberWithLoans() {
        return new Member("Carol White", "carol@example.com");
    }

    public static Loan aLoan(Book book, Member member) {
        return new Loan(book, member, LocalDate.now(), LocalDate.now().plusWeeks(2));
    }

    public static Loan anOverdueLoan(Book book, Member member) {
        return new Loan(book, member, LocalDate.now().minusWeeks(4), LocalDate.now().minusWeeks(2));
    }
}
