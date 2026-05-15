from fastapi import APIRouter, HTTPException

from bookshelf.domain.errors import BookAlreadyReturned, LoanNotFound
from bookshelf.domain.result import Failure, Success
from bookshelf.service.loan_service import LoanService

router = APIRouter(prefix="/api/loans", tags=["loans"])

_loan_service: LoanService | None = None


def init_router(loan_service: LoanService) -> APIRouter:
    global _loan_service
    _loan_service = loan_service
    return router


@router.post("")
def create_loan(book_id: int, member_id: int):
    result = _loan_service.create_loan(book_id, member_id)
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=404, detail=str(error))


@router.post("/{loan_id}/return")
def return_book(loan_id: int):
    result = _loan_service.return_book(loan_id)
    match result:
        case Success(value):
            return value
        case Failure(error):
            match error:
                case LoanNotFound():
                    raise HTTPException(status_code=404, detail=str(error))
                case BookAlreadyReturned():
                    raise HTTPException(status_code=409, detail=str(error))
                case _:
                    raise HTTPException(status_code=500, detail=str(error))


@router.get("/{loan_id}")
def find_loan(loan_id: int):
    result = _loan_service.find_loan(loan_id)
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=404, detail=str(error))
