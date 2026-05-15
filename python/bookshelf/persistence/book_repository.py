from bookshelf.domain.models import Book


class BookRepository:
    def __init__(self):
        self._store: dict[int, Book] = {}
        self._next_id: int = 1

    def save(self, book: Book) -> Book:
        if book.id is None:
            book = book.model_copy(update={"id": self._next_id})
            self._next_id += 1
        self._store[book.id] = book
        return book

    def find_by_id(self, book_id: int) -> Book | None:
        return self._store.get(book_id)

    def find_all(self) -> list[Book]:
        return list(self._store.values())

    def delete(self, book_id: int) -> None:
        self._store.pop(book_id, None)
