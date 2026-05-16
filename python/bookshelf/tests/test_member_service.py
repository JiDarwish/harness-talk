from bookshelf.domain.errors import MemberNotFound
from bookshelf.domain.result import Failure, Success


class TestMemberService:
    def test_add_member(self, member_service):
        result = member_service.add_member("Alice Johnson", "alice@example.com")

        assert isinstance(result, Success)
        member = result.value
        assert member.id is not None
        assert member.name == "Alice Johnson"
        assert member.email == "alice@example.com"

    def test_find_member(self, member_service, a_member):
        result = member_service.find_member(a_member.id)

        assert isinstance(result, Success)
        assert result.value.name == "Alice Johnson"
        assert result.value.email == "alice@example.com"

    def test_find_member_not_found(self, member_service):
        result = member_service.find_member(999)

        assert isinstance(result, Failure)
        assert isinstance(result.error, MemberNotFound)
        assert result.error.id == 999
