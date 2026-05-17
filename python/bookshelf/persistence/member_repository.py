from bookshelf.domain.models import Member


class MemberRepository:
    def __init__(self):
        self._store: dict[int, Member] = {}
        self._next_id: int = 1

    def save(self, member: Member) -> Member:
        if member.id is None:
            member = member.model_copy(update={"id": self._next_id})
            self._next_id += 1
        self._store[member.id] = member
        return member

    def find_by_id(self, member_id: int) -> Member | None:
        return self._store.get(member_id)

    def find_all(self) -> list[Member]:
        return list(self._store.values())

    def delete(self, member_id: int) -> None:
        self._store.pop(member_id, None)
