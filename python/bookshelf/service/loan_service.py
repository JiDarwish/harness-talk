from datetime import datetime

from bookshelf.domain.errors import (
    BookAlreadyReturned,
    BookNotFound,
    BookshelfError,
    LoanNotFound,
    MemberNotFound,
)
from bookshelf.domain.models import Loan
from bookshelf.domain.result import Result, failure, success
from bookshelf.persistence.book_repository import BookRepository
from bookshelf.persistence.loan_repository import LoanRepository
from bookshelf.persistence.member_repository import MemberRepository


class LoanService:
    def __init__(
        self,
        loan_repository: LoanRepository,
        book_repository: BookRepository,
        member_repository: MemberRepository,
    ):
        self._loan_repository = loan_repository
        self._book_repository = book_repository
        self._member_repository = member_repository

    def create_loan(
        self, book_id: int, member_id: int
    ) -> Result[Loan, BookshelfError]:
        book = self._book_repository.find_by_id(book_id)
        if book is None:
            return failure(BookNotFound(id=book_id))

        member = self._member_repository.find_by_id(member_id)
        if member is None:
            return failure(MemberNotFound(id=member_id))

        loan = Loan(
            book_id=book_id,
            member_id=member_id,
            borrowed_at=datetime.now(),
        )
        return success(self._loan_repository.save(loan))

    def return_book(self, loan_id: int) -> Result[Loan, BookshelfError]:
        loan = self._loan_repository.find_by_id(loan_id)
        if loan is None:
            return failure(LoanNotFound(id=loan_id))

        if loan.returned_at is not None:
            return failure(BookAlreadyReturned(loan_id=loan_id))

        updated_loan = loan.model_copy(update={"returned_at": datetime.now()})
        self._loan_repository.save(updated_loan)

        # Mark the book as available again
        book = self._book_repository.find_by_id(loan.book_id)
        if book is not None:
            updated_book = book.model_copy(update={"available": True})
            self._book_repository.save(updated_book)

        return success(updated_loan)

    def find_loan(self, loan_id: int) -> Result[Loan, BookshelfError]:
        loan = self._loan_repository.find_by_id(loan_id)
        if loan is None:
            return failure(LoanNotFound(id=loan_id))
        return success(loan)
