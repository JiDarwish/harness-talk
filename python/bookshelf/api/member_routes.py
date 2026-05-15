from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from bookshelf.persistence.member_repository import MemberRepository

router = APIRouter(prefix="/api/members", tags=["members"])

_member_repository: MemberRepository | None = None


def init_router(member_repository: MemberRepository) -> APIRouter:
    global _member_repository
    _member_repository = member_repository
    return router


class AddMemberRequest(BaseModel):
    name: str
    email: str


@router.get("/{member_id}")
def find_member(member_id: int):
    member = _member_repository.find_by_id(member_id)
    if member is None:
        raise HTTPException(status_code=404, detail=f"Member {member_id} not found")
    return member


@router.post("")
def add_member(request: AddMemberRequest):
    from bookshelf.domain.models import Member

    member = Member(name=request.name, email=request.email)
    return _member_repository.save(member)
