from __future__ import annotations

import tempfile
import unittest
from urllib.error import HTTPError, URLError
from pathlib import Path
from unittest.mock import Mock, patch

import app as iptv
from utils.m3u import (
    MAX_CHANNELS,
    MAX_PLAYLIST_BYTES,
    PlaylistImportError,
    fetch_playlist,
    parse_playlist,
)


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

    def test_import_m3u_can_replace_the_current_catalog(self) -> None:
        playlist = """#EXTM3U
#EXTINF:-1 group-title="Novo",Canal novo
https://example.com/new.m3u8
"""
        with patch("app.fetch_playlist", return_value=playlist):
            response = self.client.post(
                "/admin/importar",
                data={
                    "url": "https://example.com/new.m3u",
                    "action": "import",
                    "selected": ["0"],
                    "replace_existing": "1",
                },
                follow_redirects=True,
            )

        self.assertIn("Catálogo anterior substituído.".encode(), response.data)
        with iptv.database_connection() as database:
            channels = database.execute("SELECT nome FROM canais").fetchall()
        self.assertEqual([channel["nome"] for channel in channels], ["Canal novo"])

    def test_rejects_invalid_m3u_content(self) -> None:
        with self.assertRaises(PlaylistImportError):
            parse_playlist("isto não é uma playlist")

    def test_parses_more_than_three_thousand_channels(self) -> None:
        entries = ["#EXTM3U"]
        for index in range(3001):
            entries.extend((f"#EXTINF:-1,Canal {index}", f"https://example.com/{index}.m3u8"))

        channels = parse_playlist("\n".join(entries))

        self.assertEqual(len(channels), 3001)
        self.assertEqual(channels[-1].name, "Canal 3000")

    def test_parser_still_respects_channel_limit(self) -> None:
        entries = ["#EXTM3U"]
        for index in range(MAX_CHANNELS + 1):
            entries.extend((f"#EXTINF:-1,Canal {index}", f"https://example.com/{index}.m3u8"))

        channels = parse_playlist("\n".join(entries))

        self.assertEqual(len(channels), MAX_CHANNELS)

    def test_rejects_m3u_downloads_over_size_limit(self) -> None:
        url = "https://example.com/large.m3u"
        response = Mock()
        response.__enter__ = Mock(return_value=response)
        response.__exit__ = Mock(return_value=False)
        response.geturl.return_value = url
        response.headers.get.return_value = str(MAX_PLAYLIST_BYTES + 1)
        opener = Mock()
        opener.open.return_value = response

        with patch("utils.m3u.validate_public_url"), patch(
            "utils.m3u.build_opener", return_value=opener
        ):
            with self.assertRaisesRegex(PlaylistImportError, "50 MB"):
                fetch_playlist(url)

        response.read.assert_not_called()

    def test_reports_http_access_denied_without_exposing_playlist_url(self) -> None:
        url = "https://example.com/get.php?username=private&password=secret"
        error = HTTPError(url, 403, "Forbidden", None, None)

        with patch("utils.m3u.validate_public_url"), patch(
            "utils.m3u.build_opener"
        ) as build_opener:
            build_opener.return_value.open.side_effect = error
            with self.assertRaises(PlaylistImportError) as raised:
                fetch_playlist(url)

        self.assertIn("HTTP 403", str(raised.exception))
        self.assertNotIn("private", str(raised.exception))
        self.assertNotIn("secret", str(raised.exception))

    def test_reports_playlist_connection_timeout(self) -> None:
        url = "https://example.com/list.m3u"

        with patch("utils.m3u.validate_public_url"), patch(
            "utils.m3u.build_opener"
        ) as build_opener:
            build_opener.return_value.open.side_effect = URLError(TimeoutError())
            with self.assertRaisesRegex(PlaylistImportError, "expirou"):
                fetch_playlist(url)

    def test_fetches_xtream_live_and_movie_catalogs(self) -> None:
        url = "https://provider.example/portal/get.php?username=user&password=pass&type=m3u_plus&output=ts"
        def response(body: bytes) -> Mock:
            result = Mock()
            result.__enter__ = Mock(return_value=result)
            result.__exit__ = Mock(return_value=False)
            result.geturl.return_value = "https://provider.example/portal/player_api.php"
            result.headers.get.return_value = None
            result.read.return_value = body
            return result

        live_category_response = response(b'[{"category_id":"7","category_name":"Noticias"}]')
        live_stream_response = response(
            b'[{"stream_id":123,"name":"Canal Teste","category_id":"7",'
            b'"container_extension":"ts","stream_icon":"https://provider.example/logo.png"}]'
        )
        movie_category_response = response('[{"category_id":"9","category_name":"Ação"}]'.encode())
        movie_stream_response = response(
            b'[{"stream_id":456,"name":"Filme Teste","category_id":"9",'
            b'"container_extension":"mp4","stream_icon":"https://provider.example/movie.png"}]'
        )

        with patch("utils.m3u.validate_public_url"), patch(
            "utils.m3u.build_opener"
        ) as build_opener:
            build_opener.return_value.open.side_effect = [
                live_category_response,
                live_stream_response,
                movie_category_response,
                movie_stream_response,
            ]
            playlist = fetch_playlist(url)

        channels = parse_playlist(playlist)
        self.assertEqual(len(channels), 2)
        self.assertEqual(channels[0].name, "Canal Teste")
        self.assertEqual(channels[0].category, "Noticias")
        self.assertEqual(
            channels[0].source,
            "https://provider.example/portal/live/user/pass/123.ts",
        )
        self.assertEqual(channels[1].name, "Filme Teste")
        self.assertEqual(channels[1].category, "Filmes / Ação")
        self.assertEqual(
            channels[1].source,
            "https://provider.example/portal/movie/user/pass/456.mp4",
        )
        requested_urls = [call.args[0].full_url for call in build_opener.return_value.open.call_args_list]
        self.assertIn("action=get_live_categories", requested_urls[0])
        self.assertIn("action=get_live_streams", requested_urls[1])
        self.assertIn("action=get_vod_categories", requested_urls[2])
        self.assertIn("action=get_vod_streams", requested_urls[3])

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
