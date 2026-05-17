class TestLoanRoutes:
    def test_create_and_get_loan(self, client):
        # Add a book and a member first
        book_resp = client.post(
            "/api/books",
            json={"title": "The Great Gatsby", "author": "F. Scott Fitzgerald", "isbn": "978-0-7432-7356-5"},
        )
        book_id = book_resp.json()["id"]

        member_resp = client.post(
            "/api/members",
            json={"name": "Alice Johnson", "email": "alice@example.com"},
        )
        member_id = member_resp.json()["id"]

        # Create a loan
        loan_resp = client.post(
            "/api/loans",
            json={"book_id": book_id, "member_id": member_id},
        )
        assert loan_resp.status_code == 200
        loan = loan_resp.json()
        assert loan["book_id"] == book_id
        assert loan["member_id"] == member_id
        assert loan["borrowed_at"] is not None
        assert loan["returned_at"] is None
        loan_id = loan["id"]

        # Get the loan
        get_resp = client.get(f"/api/loans/{loan_id}")
        assert get_resp.status_code == 200
        fetched = get_resp.json()
        assert fetched["id"] == loan_id

    def test_create_loan_book_not_found(self, client):
        # Add a member but use a nonexistent book ID
        member_resp = client.post(
            "/api/members",
            json={"name": "Alice Johnson", "email": "alice@example.com"},
        )
        member_id = member_resp.json()["id"]

        response = client.post(
            "/api/loans",
            json={"book_id": 999, "member_id": member_id},
        )
        assert response.status_code == 404

    def test_return_loan(self, client):
        # Set up book and member
        book_resp = client.post(
            "/api/books",
            json={"title": "The Great Gatsby", "author": "F. Scott Fitzgerald", "isbn": "978-0-7432-7356-5"},
        )
        book_id = book_resp.json()["id"]

        member_resp = client.post(
            "/api/members",
            json={"name": "Alice Johnson", "email": "alice@example.com"},
        )
        member_id = member_resp.json()["id"]

        # Create and return a loan
        loan_resp = client.post(
            "/api/loans",
            json={"book_id": book_id, "member_id": member_id},
        )
        loan_id = loan_resp.json()["id"]

        return_resp = client.post(f"/api/loans/{loan_id}/return")
        assert return_resp.status_code == 200
        returned = return_resp.json()
        assert returned["returned_at"] is not None

    def test_return_already_returned_loan(self, client):
        # Set up book, member, and loan
        book_resp = client.post(
            "/api/books",
            json={"title": "The Great Gatsby", "author": "F. Scott Fitzgerald", "isbn": "978-0-7432-7356-5"},
        )
        book_id = book_resp.json()["id"]

        member_resp = client.post(
            "/api/members",
            json={"name": "Alice Johnson", "email": "alice@example.com"},
        )
        member_id = member_resp.json()["id"]

        loan_resp = client.post(
            "/api/loans",
            json={"book_id": book_id, "member_id": member_id},
        )
        loan_id = loan_resp.json()["id"]

        # Return once
        client.post(f"/api/loans/{loan_id}/return")

        # Try to return again
        second_return = client.post(f"/api/loans/{loan_id}/return")
        assert second_return.status_code == 409

    def test_get_loan_not_found(self, client):
        response = client.get("/api/loans/999")

        assert response.status_code == 404
