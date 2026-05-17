from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from bookshelf.domain.result import Failure, Success
from bookshelf.service.book_service import BookService

router = APIRouter(prefix="/api/books", tags=["books"])

_book_service: BookService | None = None


def init_router(book_service: BookService) -> APIRouter:
    global _book_service
    _book_service = book_service
    return router


class AddBookRequest(BaseModel):
    title: str
    author: str
    isbn: str


@router.get("")
def find_all_books():
    result = _book_service.find_all_books()
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=500, detail=str(error))


@router.get("/{book_id}")
def find_book(book_id: int):
    result = _book_service.find_book(book_id)
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=404, detail=str(error))


@router.post("")
def add_book(request: AddBookRequest):
    result = _book_service.add_book(request.title, request.author, request.isbn)
    match result:
        case Success(value):
            return value
        case Failure(error):
            raise HTTPException(status_code=500, detail=str(error))
