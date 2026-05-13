package com.example.bookshelf.persistence;

import com.example.bookshelf.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {
}
