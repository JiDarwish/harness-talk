package com.example.bookshelf.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import java.time.LocalDate;

@Entity
public class Loan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Book book;

    @ManyToOne
    private Member member;

    private LocalDate borrowedAt;
    private LocalDate dueDate;
    private LocalDate returnedAt;

    protected Loan() {}

    public Loan(Book book, Member member, LocalDate borrowedAt, LocalDate dueDate) {
        this.book = book;
        this.member = member;
        this.borrowedAt = borrowedAt;
        this.dueDate = dueDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Book getBook() { return book; }
    public Member getMember() { return member; }
    public LocalDate getBorrowedAt() { return borrowedAt; }
    public LocalDate getDueDate() { return dueDate; }
    public LocalDate getReturnedAt() { return returnedAt; }
    public void setReturnedAt(LocalDate returnedAt) { this.returnedAt = returnedAt; }
}
