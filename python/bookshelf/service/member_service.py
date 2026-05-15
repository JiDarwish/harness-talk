from bookshelf.domain.errors import BookshelfError, MemberNotFound
from bookshelf.domain.models import Member
from bookshelf.domain.result import Result, failure, success
from bookshelf.persistence.member_repository import MemberRepository


class MemberService:
    def __init__(self, member_repository: MemberRepository):
        self._member_repository = member_repository

    def find_member(self, id: int) -> Result[Member, BookshelfError]:
        member = self._member_repository.find_by_id(id)
        if member is None:
            return failure(MemberNotFound(id=id))
        return success(member)

    def add_member(self, name: str, email: str) -> Result[Member, BookshelfError]:
        member = Member(name=name, email=email)
        return success(self._member_repository.save(member))
