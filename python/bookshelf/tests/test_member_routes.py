class TestMemberRoutes:
    def test_add_and_get_member(self, client):
        add_resp = client.post(
            "/api/members",
            json={"name": "Alice Johnson", "email": "alice@example.com"},
        )
        assert add_resp.status_code == 200
        member = add_resp.json()
        assert member["name"] == "Alice Johnson"
        assert member["email"] == "alice@example.com"
        member_id = member["id"]

        get_resp = client.get(f"/api/members/{member_id}")
        assert get_resp.status_code == 200
        fetched = get_resp.json()
        assert fetched["id"] == member_id
        assert fetched["name"] == "Alice Johnson"

    def test_get_member_not_found(self, client):
        response = client.get("/api/members/999")

        assert response.status_code == 404
