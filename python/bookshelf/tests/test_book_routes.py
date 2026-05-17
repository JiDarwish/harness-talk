class TestBookRoutes:
    def test_get_books_empty(self, client):
        response = client.get("/api/books")

        assert response.status_code == 200
        assert response.json() == []

    def test_add_and_get_book(self, client):
        # Add a book
        add_response = client.post(
            "/api/books",
            json={"title": "The Great Gatsby", "author": "F. Scott Fitzgerald", "isbn": "978-0-7432-7356-5"},
        )
        assert add_response.status_code == 200
        book = add_response.json()
        assert book["title"] == "The Great Gatsby"
        assert book["author"] == "F. Scott Fitzgerald"
        assert book["isbn"] == "978-0-7432-7356-5"
        assert book["available"] is True
        book_id = book["id"]

        # Get the book by ID
        get_response = client.get(f"/api/books/{book_id}")
        assert get_response.status_code == 200
        fetched = get_response.json()
        assert fetched["id"] == book_id
        assert fetched["title"] == "The Great Gatsby"

    def test_get_book_not_found(self, client):
        response = client.get("/api/books/999")

        assert response.status_code == 404
