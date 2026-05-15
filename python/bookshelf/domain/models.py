from datetime import datetime

from pydantic import BaseModel


class Book(BaseModel):
    id: int | None = None
    title: str
    author: str
    isbn: str
    available: bool = True


class Member(BaseModel):
    id: int | None = None
    name: str
    email: str


class Loan(BaseModel):
    id: int | None = None
    book_id: int
    member_id: int
    borrowed_at: datetime
    returned_at: datetime | None = None
