from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import app as iptv
from utils.m3u import PlaylistImportError, parse_playlist


class IPTVCaseiroTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        root = Path(self.temporary_directory.name)
        iptv.DATABASE_DIR = root / "database"
        iptv.DATABASE_PATH = iptv.DATABASE_DIR / "banco.db"
        iptv.VIDEO_DIR = root / "videos"
        iptv.PLAYLIST_DIR = root / "playlists"
        iptv.PLAYLIST_CACHE_PATH = iptv.PLAYLIST_DIR / "br.m3u"
        iptv.app.config.update(TESTING=True, SERVER_NAME="localhost:5000")
        iptv.init_db()
        self.client = iptv.app.test_client()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_initial_catalog_player_and_playlist(self) -> None:
        home = self.client.get("/")
        self.assertEqual(home.status_code, 200)
        self.assertIn("Canal de Teste".encode(), home.data)

        player = self.client.get("/player/1")
        self.assertEqual(player.status_code, 200)
        self.assertIn("Vídeo de teste ainda não encontrado".encode(), player.data)

        playlist = self.client.get("/playlist.m3u")
        self.assertEqual(playlist.status_code, 200)
        self.assertIn(b"#EXTM3U", playlist.data)
        self.assertIn(b"http://localhost:5000/videos/video-teste.mp4", playlist.data)

    def test_local_video_route_and_traversal_protection(self) -> None:
        test_video = iptv.VIDEO_DIR / "video-teste.mp4"
        test_video.write_bytes(b"test-video-content")

        response = self.client.get("/videos/video-teste.mp4")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.data, b"test-video-content")
        response.close()
        self.assertEqual(self.client.get("/videos/../app.py").status_code, 404)
        self.assertEqual(self.client.get("/videos/documento.txt").status_code, 404)

    def test_admin_crud_and_validation(self) -> None:
        invalid = self.client.post(
            "/admin",
            data={"nome": "Inválido", "tipo": "arquivo", "fonte": "../segredo.mp4"},
        )
        self.assertEqual(invalid.status_code, 200)
        self.assertIn("sem pastas".encode(), invalid.data)

        created = self.client.post(
            "/admin",
            data={
                "nome": "Stream autorizado",
                "descricao": "Teste HLS",
                "categoria": "Eventos",
                "logo": "https://example.com/logo.png",
                "tipo": "stream",
                "fonte": "https://example.com/live.m3u8",
                "ativo": "on",
            },
            follow_redirects=True,
        )
        self.assertEqual(created.status_code, 200)
        self.assertIn("Stream autorizado".encode(), created.data)

        toggled = self.client.post("/admin/alternar/2", follow_redirects=True)
        self.assertEqual(toggled.status_code, 200)
        playlist = self.client.get("/playlist.m3u")
        self.assertNotIn(b"https://example.com/live.m3u8", playlist.data)

        deleted = self.client.post("/admin/excluir/2", follow_redirects=True)
        self.assertEqual(deleted.status_code, 200)
        self.assertNotIn("Stream autorizado".encode(), deleted.data)

    def test_parse_and_import_m3u_playlist(self) -> None:
        sample_playlist = """#EXTM3U
#EXTINF:-1 tvg-logo="https://example.com/a.png" group-title="Públicos",Canal A
https://example.com/a.m3u8
#EXTINF:-1 group-title="Legislativo",Canal B
https://example.com/b.m3u8
"""
        parsed = parse_playlist(sample_playlist)
        self.assertEqual(len(parsed), 2)
        self.assertEqual(parsed[0].name, "Canal A")
        self.assertEqual(parsed[0].category, "Públicos")

        with patch("app.fetch_playlist", return_value=sample_playlist):
            preview = self.client.post(
                "/admin/importar",
                data={"url": "https://example.com/br.m3u", "action": "preview"},
            )
            self.assertEqual(preview.status_code, 200)
            self.assertIn(b"Canal A", preview.data)

            imported = self.client.post(
                "/admin/importar",
                data={
                    "url": "https://example.com/br.m3u",
                    "action": "import",
                    "selected": ["0", "1"],
                },
                follow_redirects=True,
            )
            self.assertEqual(imported.status_code, 200)
            self.assertIn("2 canal(is) importado(s).".encode(), imported.data)

            duplicate = self.client.post(
                "/admin/importar",
                data={
                    "url": "https://example.com/br.m3u",
                    "action": "import",
                    "selected": ["0"],
                },
                follow_redirects=True,
            )
            self.assertIn("1 duplicado(s) ignorado(s).".encode(), duplicate.data)

        with iptv.database_connection() as database:
            imported_count = database.execute(
                "SELECT COUNT(*) FROM canais WHERE descricao = ?",
                ("Importado de playlist M3U.",),
            ).fetchone()[0]
        self.assertEqual(imported_count, 2)

    def test_rejects_invalid_m3u_content(self) -> None:
        with self.assertRaises(PlaylistImportError):
            parse_playlist("isto não é uma playlist")

    def test_uses_local_playlist_when_network_is_unavailable(self) -> None:
        iptv.PLAYLIST_CACHE_PATH.write_text(
            "#EXTM3U\n#EXTINF:-1 group-title=\"Públicos\",Canal Offline\n"
            "https://example.com/offline.m3u8\n",
            encoding="utf-8",
        )
        with patch("app.fetch_playlist", side_effect=PlaylistImportError("Sem rede")):
            response = self.client.post(
                "/admin/importar",
                data={"url": iptv.DEFAULT_PLAYLIST_URL, "action": "preview"},
            )
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"Canal Offline", response.data)
        self.assertIn("cópia local".encode(), response.data)

    def test_deletes_multiple_selected_channels(self) -> None:
        with iptv.database_connection() as database:
            created_at = "2026-01-01T00:00:00+00:00"
            for index in range(3):
                database.execute(
                    """
                    INSERT INTO canais
                        (nome, descricao, categoria, logo, tipo, fonte, ativo, criado_em)
                    VALUES (?, '', 'Teste', '', 'stream', ?, 1, ?)
                    """,
                    (f"Canal {index}", f"https://example.com/{index}.m3u8", created_at),
                )

        response = self.client.post(
            "/admin/excluir-varios",
            data={"selected": ["2", "4", "texto-inválido"]},
            follow_redirects=True,
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn("2 canal(is) excluído(s).".encode(), response.data)
        with iptv.database_connection() as database:
            remaining_ids = {
                row["id"] for row in database.execute("SELECT id FROM canais").fetchall()
            }
        self.assertEqual(remaining_ids, {1, 3})

    def test_player_has_channel_and_volume_controls(self) -> None:
        with iptv.database_connection() as database:
            database.execute(
                """
                INSERT INTO canais
                    (nome, descricao, categoria, logo, tipo, fonte, ativo, criado_em)
                VALUES ('Canal Seguinte', '', 'Teste', '', 'stream', ?, 1, ?)
                """,
                ("https://example.com/next.m3u8", "2026-01-01T00:00:00+00:00"),
            )

        response = self.client.get("/player/1")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'id="volume-down"', response.data)
        self.assertIn(b'id="volume-up"', response.data)
        self.assertIn(b"/player/2?autoplay=1", response.data)

    def test_favorite_channel_is_protected_from_deletion(self) -> None:
        favorite = self.client.post("/player/1/favorito", follow_redirects=True)
        self.assertEqual(favorite.status_code, 200)
        self.assertIn(b"bi-star-fill", favorite.data)

        bulk_delete = self.client.post(
            "/admin/excluir-varios",
            data={"selected": ["1"]},
            follow_redirects=True,
        )
        self.assertIn("1 favorito(s) preservado(s).".encode(), bulk_delete.data)

        single_delete = self.client.post("/admin/excluir/1", follow_redirects=True)
        self.assertIn("Remova o canal dos favoritos".encode(), single_delete.data)

        with iptv.database_connection() as database:
            channel = database.execute(
                "SELECT favorito FROM canais WHERE id = 1"
            ).fetchone()
        self.assertIsNotNone(channel)
        self.assertEqual(channel["favorito"], 1)

        self.client.post("/player/1/favorito")
        deleted = self.client.post("/admin/excluir/1", follow_redirects=True)
        self.assertIn("Canal excluído.".encode(), deleted.data)

    def test_catalog_separates_favorites_and_allows_removing_them(self) -> None:
        with iptv.database_connection() as database:
            database.execute(
                """
                INSERT INTO canais
                    (nome, descricao, categoria, logo, tipo, fonte, ativo, criado_em)
                VALUES ('Canal comum', '', 'Teste', '', 'stream', ?, 1, ?)
                """,
                ("https://example.com/common.m3u8", "2026-01-01T00:00:00+00:00"),
            )

        self.client.post("/player/1/favorito")
        catalog = self.client.get("/")
        self.assertIn(b'id="favorite-heading"', catalog.data)
        self.assertIn(b'id="other-heading"', catalog.data)
        self.assertIn(b"Canal de Teste", catalog.data)
        self.assertIn(b"Canal comum", catalog.data)

        favorites = self.client.get("/?favoritos=1")
        self.assertIn(b"Canal de Teste", favorites.data)
        self.assertNotIn(b"Canal comum", favorites.data)

        removed = self.client.post(
            "/player/1/favorito",
            data={"origem": "catalogo", "favoritos": "1"},
            follow_redirects=True,
        )
        self.assertIn("Agora ele pode ser excluído".encode(), removed.data)
        self.assertIn("Nenhum canal favorito".encode(), removed.data)

        admin = self.client.get("/admin")
        self.assertNotIn(b"Canal favorito protegido contra exclusao", admin.data)
        deleted = self.client.post("/admin/excluir/1", follow_redirects=True)
        self.assertIn("Canal excluído.".encode(), deleted.data)

    def test_admin_can_remove_favorite_before_deletion(self) -> None:
        self.client.post("/player/1/favorito")
        admin = self.client.get("/admin")
        self.assertIn(b'remove-favorite-icon', admin.data)

        removed = self.client.post(
            "/player/1/favorito",
            data={"origem": "admin"},
            follow_redirects=True,
        )
        self.assertIn("Agora ele pode ser excluído".encode(), removed.data)
        self.assertNotIn(b'remove-favorite-icon', removed.data)

    def test_external_channel_opens_official_site_and_is_not_in_m3u(self) -> None:
        official_url = "https://globoplay.globo.com/tv-globo/ao-vivo/6120663/"
        created = self.client.post(
            "/admin",
            data={
                "nome": "TV Globo",
                "descricao": "Transmissão oficial pelo Globoplay.",
                "categoria": "Canais abertos",
                "logo": "",
                "tipo": "externo",
                "fonte": official_url,
                "ativo": "on",
            },
            follow_redirects=True,
        )
        self.assertEqual(created.status_code, 200)
        self.assertIn(b"TV Globo", created.data)

        catalog = self.client.get("/")
        self.assertIn(b"Abrir site", catalog.data)
        self.assertIn(b'target="_blank"', catalog.data)

        external = self.client.get("/canal/2/abrir")
        self.assertEqual(external.status_code, 302)
        self.assertEqual(external.location, official_url)
        self.assertEqual(self.client.get("/player/2").status_code, 302)

        playlist = self.client.get("/playlist.m3u")
        self.assertNotIn(official_url.encode(), playlist.data)


if __name__ == "__main__":
    unittest.main()
