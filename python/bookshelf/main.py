from fastapi import FastAPI

from bookshelf.api import book_routes, loan_routes, member_routes
from bookshelf.config.feature_flags import FeatureFlags
from bookshelf.config.settings import Settings
from bookshelf.persistence.book_repository import BookRepository
from bookshelf.persistence.loan_repository import LoanRepository
from bookshelf.persistence.member_repository import MemberRepository
from bookshelf.service.book_service import BookService
from bookshelf.service.loan_service import LoanService
from bookshelf.service.member_service import MemberService

# Configuration
settings = Settings()
feature_flags = FeatureFlags()

# Repositories
book_repository = BookRepository()
member_repository = MemberRepository()
loan_repository = LoanRepository()

# Services
book_service = BookService(book_repository)
loan_service = LoanService(loan_repository, book_repository, member_repository)
member_service = MemberService(member_repository)

# FastAPI app
app = FastAPI(title=settings.app_name, debug=settings.debug)

# Wire up routers
app.include_router(book_routes.init_router(book_service))
app.include_router(member_routes.init_router(member_service))
app.include_router(loan_routes.init_router(loan_service))


@app.get("/")
def root():
    return {"app": settings.app_name, "status": "running"}
