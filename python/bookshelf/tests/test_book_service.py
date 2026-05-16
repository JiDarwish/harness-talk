from bookshelf.domain.errors import BookNotFound
from bookshelf.domain.result import Failure, Success


class TestBookService:
    def test_add_book(self, book_service):
        result = book_service.add_book("The Great Gatsby", "F. Scott Fitzgerald", "978-0-7432-7356-5")

        assert isinstance(result, Success)
        book = result.value
        assert book.id is not None
        assert book.title == "The Great Gatsby"
        assert book.author == "F. Scott Fitzgerald"
        assert book.isbn == "978-0-7432-7356-5"
        assert book.available is True

    def test_find_book(self, book_service, a_book):
        result = book_service.find_book(a_book.id)

        assert isinstance(result, Success)
        assert result.value.title == "The Great Gatsby"
        assert result.value.author == "F. Scott Fitzgerald"

    def test_find_book_not_found(self, book_service):
        result = book_service.find_book(999)

        assert isinstance(result, Failure)
        assert isinstance(result.error, BookNotFound)
        assert result.error.id == 999

    def test_find_all_books(self, book_service):
        book_service.add_book("The Great Gatsby", "F. Scott Fitzgerald", "978-0-7432-7356-5")
        book_service.add_book("1984", "George Orwell", "978-0-451-52493-5")

        result = book_service.find_all_books()

        assert isinstance(result, Success)
        assert len(result.value) == 2

    def test_borrow_book(self, book_service, a_book):
        result = book_service.borrow_book(a_book.id)

        assert isinstance(result, Success)
        assert result.value.available is False

    def test_borrow_book_not_found(self, book_service):
        result = book_service.borrow_book(999)

        assert isinstance(result, Failure)
        assert isinstance(result.error, BookNotFound)
