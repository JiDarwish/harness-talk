import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from bookshelf.api import book_routes, loan_routes, member_routes
from bookshelf.domain.models import Book, Member
from bookshelf.persistence.book_repository import BookRepository
from bookshelf.persistence.loan_repository import LoanRepository
from bookshelf.persistence.member_repository import MemberRepository
from bookshelf.service.book_service import BookService
from bookshelf.service.loan_service import LoanService
from bookshelf.service.member_service import MemberService


# --- Repositories ---


@pytest.fixture
def book_repo():
    return BookRepository()


@pytest.fixture
def member_repo():
    return MemberRepository()


@pytest.fixture
def loan_repo():
    return LoanRepository()


# --- Services ---


@pytest.fixture
def book_service(book_repo):
    return BookService(book_repo)


@pytest.fixture
def loan_service(loan_repo, book_repo, member_repo):
    return LoanService(loan_repo, book_repo, member_repo)


@pytest.fixture
def member_service(member_repo):
    return MemberService(member_repo)


# --- Fixtures for pre-saved domain objects ---


@pytest.fixture
def a_book(book_repo):
    return book_repo.save(
        Book(title="The Great Gatsby", author="F. Scott Fitzgerald", isbn="978-0-7432-7356-5")
    )


@pytest.fixture
def a_member(member_repo):
    return member_repo.save(Member(name="Alice Johnson", email="alice@example.com"))


# --- TestClient ---


@pytest.fixture
def client(book_service, loan_service, member_service):
    app = FastAPI(title="Bookshelf Test")
    app.include_router(book_routes.init_router(book_service))
    app.include_router(member_routes.init_router(member_service))
    app.include_router(loan_routes.init_router(loan_service))
    return TestClient(app)
