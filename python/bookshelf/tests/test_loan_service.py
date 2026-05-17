import pytest

from bookshelf.domain.errors import BookAlreadyReturned, BookNotAvailable, BookNotFound, MemberNotFound
from bookshelf.domain.result import Failure, Success


class TestLoanService:
    def test_create_loan(self, loan_service, book_service, a_book, a_member):
        result = loan_service.create_loan(a_book.id, a_member.id)

        assert isinstance(result, Success)
        loan = result.value
        assert loan.id is not None
        assert loan.book_id == a_book.id
        assert loan.member_id == a_member.id
        assert loan.borrowed_at is not None
        assert loan.returned_at is None

        # Verify the book is now marked as unavailable
        book_result = book_service.find_book(a_book.id)
        assert isinstance(book_result, Success)
        assert book_result.value.available is False

    def test_create_loan_book_not_found(self, loan_service, a_member):
        result = loan_service.create_loan(999, a_member.id)

        assert isinstance(result, Failure)
        assert isinstance(result.error, BookNotFound)

    def test_create_loan_member_not_found(self, loan_service, a_book):
        result = loan_service.create_loan(a_book.id, 999)

        assert isinstance(result, Failure)
        assert isinstance(result.error, MemberNotFound)

    def test_return_book(self, loan_service, book_service, a_book, a_member):
        create_result = loan_service.create_loan(a_book.id, a_member.id)
        assert isinstance(create_result, Success)
        loan_id = create_result.value.id

        result = loan_service.return_book(loan_id)

        assert isinstance(result, Success)
        assert result.value.returned_at is not None

        # Verify the book is available again
        book_result = book_service.find_book(a_book.id)
        assert isinstance(book_result, Success)
        assert book_result.value.available is True

    def test_return_already_returned(self, loan_service, a_book, a_member):
        create_result = loan_service.create_loan(a_book.id, a_member.id)
        assert isinstance(create_result, Success)
        loan_id = create_result.value.id

        # Return once
        loan_service.return_book(loan_id)

        # Try to return again
        result = loan_service.return_book(loan_id)

        assert isinstance(result, Failure)
        assert isinstance(result.error, BookAlreadyReturned)
        assert result.error.loan_id == loan_id

    @pytest.mark.skip(reason="Known bug: borrow_book does not check availability")
    def test_borrow_book_without_availability_check(self, loan_service, book_service, a_book, a_member):
        # Borrow the book via a loan (marks it unavailable)
        loan_service.create_loan(a_book.id, a_member.id)

        # Attempting to borrow an already-unavailable book should fail
        result = book_service.borrow_book(a_book.id)

        assert isinstance(result, Failure)
        assert isinstance(result.error, BookNotAvailable)
