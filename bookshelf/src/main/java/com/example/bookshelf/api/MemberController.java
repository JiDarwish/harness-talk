package com.example.bookshelf.api;

import com.example.bookshelf.domain.Member;
import com.example.bookshelf.persistence.MemberRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
public class MemberController {
    private final MemberRepository memberRepository;

    public MemberController(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    record AddMemberRequest(String name, String email) {}

    @PostMapping
    public ResponseEntity<Member> addMember(@RequestBody AddMemberRequest request) {
        var member = new Member(request.name(), request.email());
        return ResponseEntity.ok(memberRepository.save(member));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Member> findMember(@PathVariable Long id) {
        return memberRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
