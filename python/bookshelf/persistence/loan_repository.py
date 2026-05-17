from bookshelf.domain.models import Loan


class LoanRepository:
    def __init__(self):
        self._store: dict[int, Loan] = {}
        self._next_id: int = 1

    def save(self, loan: Loan) -> Loan:
        if loan.id is None:
            loan = loan.model_copy(update={"id": self._next_id})
            self._next_id += 1
        self._store[loan.id] = loan
        return loan

    def find_by_id(self, loan_id: int) -> Loan | None:
        return self._store.get(loan_id)

    def find_all(self) -> list[Loan]:
        return list(self._store.values())

    def find_by_book_id(self, book_id: int) -> list[Loan]:
        return [loan for loan in self._store.values() if loan.book_id == book_id]

    def delete(self, loan_id: int) -> None:
        self._store.pop(loan_id, None)
