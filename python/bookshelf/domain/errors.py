from dataclasses import dataclass


@dataclass(frozen=True)
class BookNotFound:
    id: int


@dataclass(frozen=True)
class MemberNotFound:
    id: int


@dataclass(frozen=True)
class BookNotAvailable:
    book_id: int


@dataclass(frozen=True)
class LoanNotFound:
    id: int


@dataclass(frozen=True)
class BookAlreadyReturned:
    loan_id: int


type BookshelfError = (
    BookNotFound | MemberNotFound | BookNotAvailable | LoanNotFound | BookAlreadyReturned
)
