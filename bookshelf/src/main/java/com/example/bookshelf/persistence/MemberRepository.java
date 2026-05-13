package com.example.bookshelf.persistence;

import com.example.bookshelf.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
}
