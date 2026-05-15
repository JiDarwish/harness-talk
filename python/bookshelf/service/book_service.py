from bookshelf.domain.errors import BookNotFound, BookshelfError
from bookshelf.domain.models import Book
from bookshelf.domain.result import Result, failure, success
from bookshelf.persistence.book_repository import BookRepository


class BookService:
    def __init__(self, book_repository: BookRepository):
        self._book_repository = book_repository

    def find_book(self, book_id: int) -> Result[Book, BookshelfError]:
        book = self._book_repository.find_by_id(book_id)
        if book is None:
            return failure(BookNotFound(id=book_id))
        return success(book)

    def find_all_books(self) -> Result[list[Book], BookshelfError]:
        return success(self._book_repository.find_all())

    def add_book(self, title: str, author: str, isbn: str) -> Result[Book, BookshelfError]:
        book = Book(title=title, author=author, isbn=isbn)
        return success(self._book_repository.save(book))

    # BUG: does not check book.available before creating the loan.
    # This allows a book to be borrowed even when it's already on loan.
    def borrow_book(self, book_id: int) -> Result[Book, BookshelfError]:
        book = self._book_repository.find_by_id(book_id)
        if book is None:
            return failure(BookNotFound(id=book_id))

        # Missing: if not book.available: return failure(BookNotAvailable(book_id=book_id))
        updated = book.model_copy(update={"available": False})
        self._book_repository.save(updated)
        return success(updated)
