package com.example.bookshelf.api;

import com.example.bookshelf.BookshelfFixtures;
import com.example.bookshelf.persistence.BookRepository;
import com.example.bookshelf.persistence.LoanRepository;
import com.example.bookshelf.persistence.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BookControllerTest {

    @Autowired MockMvc mockMvc;
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
    void addBook_returnsCreatedBook() throws Exception {
        mockMvc.perform(post("/api/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Clean Code","author":"Robert C. Martin","isbn":"978-0132350884"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Clean Code"))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void findBook_existingBook_returnsBook() throws Exception {
        var book = bookRepository.save(BookshelfFixtures.aBook());

        mockMvc.perform(get("/api/books/" + book.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Clean Code"));
    }

    @Test
    void findBook_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/books/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void borrowBook_success_returnsLoan() throws Exception {
        var book = bookRepository.save(BookshelfFixtures.aBook());
        var member = memberRepository.save(BookshelfFixtures.aMember());

        mockMvc.perform(post("/api/books/" + book.getId() + "/borrow")
                        .param("memberId", member.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.book.id").value(book.getId()));
    }

    @Test
    void findAllBooks_returnsBooks() throws Exception {
        bookRepository.save(BookshelfFixtures.aBook());
        bookRepository.save(BookshelfFixtures.anotherBook());

        mockMvc.perform(get("/api/books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
