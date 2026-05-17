from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from bookshelf.domain.result import Failure, Success
from bookshelf.service.member_service import MemberService

router = APIRouter(prefix="/api/members", tags=["members"])

_member_service: MemberService | None = None


def init_router(member_service: MemberService) -> APIRouter:
    global _member_service
    _member_service = member_service
    return router


class AddMemberRequest(BaseModel):
    name: str
    email: str


@router.get("/{member_id}")
def find_member(member_id: int):
    result = _member_service.find_member(member_id)
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=404, detail=str(error))


@router.post("")
def add_member(request: AddMemberRequest):
    result = _member_service.add_member(request.name, request.email)
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=500, detail=str(error))
