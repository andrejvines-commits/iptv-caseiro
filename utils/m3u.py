from __future__ import annotations

import ipaddress
import json
import re
import socket
import ssl
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, quote, urlencode, urlparse, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_PLAYLIST_BYTES = 50 * 1024 * 1024
MAX_XTREAM_RESPONSE_BYTES = 64 * 1024 * 1024
MAX_CHANNELS = 100_000
MAX_XTREAM_ITEMS_PER_TYPE = 5000
REQUEST_TIMEOUT_SECONDS = 60
ATTRIBUTE_PATTERN = re.compile(r'([\w-]+)="([^"]*)"')


class PlaylistImportError(ValueError):
    pass


@dataclass(frozen=True)
class PlaylistChannel:
    name: str
    category: str
    logo: str
    source: str


def validate_public_url(value: str) -> None:
    try:
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise PlaylistImportError("Informe uma URL HTTP ou HTTPS válida.")
        if parsed.username or parsed.password:
            raise PlaylistImportError("A URL não pode conter usuário ou senha.")
        default_port = 80 if parsed.scheme == "http" else 443
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or default_port, type=socket.SOCK_STREAM)
    except (ValueError, OSError) as error:
        raise PlaylistImportError("Não foi possível localizar o servidor da playlist.") from error

    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if not ip.is_global:
            raise PlaylistImportError("Por segurança, use uma playlist hospedada em endereço público.")


class PublicRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_playlist(url: str) -> str:
    query = parse_qs(urlsplit(url).query)
    username = query.get("username", [""])[0]
    password = query.get("password", [""])[0]
    if username and password:
        return _fetch_xtream_playlist(url, username, password)
    return _fetch_url(url, "application/x-mpegURL, audio/x-mpegurl, text/plain, */*", MAX_PLAYLIST_BYTES).decode(
        "utf-8-sig", errors="replace"
    )


def _fetch_url(url: str, accept: str, maximum: int) -> bytes:
    validate_public_url(url)
    request = Request(
        url,
        headers={
            "User-Agent": "IPTV-Caseiro/1.0",
            "Accept": accept,
        },
    )
    opener = build_opener(PublicRedirectHandler())
    try:
        with opener.open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            validate_public_url(response.geturl())
            declared_size = response.headers.get("Content-Length")
            if declared_size and int(declared_size) > maximum:
                raise PlaylistImportError(_size_limit_message(maximum))
            content = response.read(maximum + 1)
            if len(content) > maximum:
                raise PlaylistImportError(_size_limit_message(maximum))
    except PlaylistImportError:
        raise
    except HTTPError as error:
        if error.code in {401, 403}:
            message = (
                f"O servidor recusou o acesso (HTTP {error.code}). "
                "Confira os dados da assinatura e se o provedor permite acesso por este aplicativo."
            )
        elif error.code == 404:
            message = "O servidor não encontrou essa lista (HTTP 404). Confira o endereço fornecido pelo provedor."
        elif error.code == 429:
            message = "O servidor limitou as tentativas (HTTP 429). Aguarde e tente novamente."
        else:
            message = f"O servidor respondeu com erro HTTP {error.code} ao baixar a playlist."
        raise PlaylistImportError(message) from error
    except URLError as error:
        if isinstance(error.reason, (socket.timeout, TimeoutError)):
            message = "A conexão com o servidor expirou. Tente novamente ou confira se o provedor está disponível."
        elif isinstance(error.reason, socket.gaierror):
            message = "Não foi possível localizar o servidor da playlist. Confira o endereço e a conexão com a internet."
        elif isinstance(error.reason, ssl.SSLError):
            message = "Não foi possível estabelecer uma conexão segura com o servidor da playlist."
        else:
            message = "Não foi possível conectar ao servidor da playlist. Confira a conexão e tente novamente."
        raise PlaylistImportError(message) from error
    except (TimeoutError, OSError, ValueError) as error:
        raise PlaylistImportError("Não foi possível baixar a playlist. Verifique a URL e tente novamente.") from error

    return content


def _size_limit_message(maximum: int) -> str:
    return f"A playlist ou resposta do servidor ultrapassa o limite de {maximum // (1024 * 1024)} MB."


def _fetch_xtream_playlist(url: str, username: str, password: str) -> str:
    parsed = urlsplit(url)
    directory = parsed.path.rsplit("/", 1)[0]
    api_path = f"{directory}/player_api.php"
    api_base = urlunsplit((parsed.scheme, parsed.netloc, api_path, "", ""))

    def api_url(action: str) -> str:
        return f"{api_base}?{urlencode({'username': username, 'password': password, 'action': action})}"

    try:
        live_categories_content = _fetch_url(
            api_url("get_live_categories"), "application/json", 8 * 1024 * 1024
        )
        live_streams_content = _fetch_url(
            api_url("get_live_streams"), "application/json", MAX_XTREAM_RESPONSE_BYTES
        )
        movie_categories_content = _fetch_url(
            api_url("get_vod_categories"), "application/json", 8 * 1024 * 1024
        )
        movie_streams_content = _fetch_url(
            api_url("get_vod_streams"), "application/json", MAX_XTREAM_RESPONSE_BYTES
        )
        live_categories_data = json.loads(live_categories_content.decode("utf-8-sig"))
        live_streams_data = json.loads(live_streams_content.decode("utf-8-sig"))
        movie_categories_data = json.loads(movie_categories_content.decode("utf-8-sig"))
        movie_streams_data = json.loads(movie_streams_content.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PlaylistImportError("O provedor não retornou dados Xtream válidos.") from error

    if any(
        not isinstance(data, list)
        for data in (
            live_categories_data,
            live_streams_data,
            movie_categories_data,
            movie_streams_data,
        )
    ):
        raise PlaylistImportError(
            "O provedor não retornou a lista Xtream esperada. Confira a assinatura e o acesso à API."
        )

    parsed = urlsplit(api_base)
    stream_directory = parsed.path.rsplit("/", 1)[0]
    playlist_lines = ["#EXTM3U"]

    def attribute(value: str) -> str:
        return value.replace("&", "&amp;").replace('"', "&quot;").replace("\r", " ").replace("\n", " ")

    def append_items(
        categories_data: list,
        items: list,
        item_id_key: str,
        item_path: str,
        *,
        default_name: str,
        logo_key: str,
        category_prefix: str = "",
    ) -> None:
        categories = {
            str(category.get("category_id", "")): str(category.get("category_name") or "Sem categoria")
            for category in categories_data
            if isinstance(category, dict)
        }
        for item in items[:MAX_XTREAM_ITEMS_PER_TYPE]:
            if not isinstance(item, dict):
                continue
            item_id = str(item.get(item_id_key, "")).strip()
            if not item_id:
                continue
            extension = re.sub(r"[^a-zA-Z0-9]", "", str(item.get("container_extension") or "ts")) or "ts"
            source_path = (
                f"{stream_directory}/{item_path}/{quote(username, safe='')}/{quote(password, safe='')}/"
                f"{quote(item_id, safe='')}.{extension}"
            )
            source = urlunsplit((parsed.scheme, parsed.netloc, source_path, "", ""))
            name = str(item.get("name") or item.get("title") or default_name)
            category = categories.get(str(item.get("category_id", "")), "Sem categoria")
            logo = str(item.get(logo_key) or "")
            logo_attribute = f' tvg-logo="{attribute(logo)}"' if logo.startswith(("http://", "https://")) else ""
            playlist_lines.append(
                f'#EXTINF:-1{logo_attribute} group-title="{attribute(category_prefix + category)}",'
                f'{name.replace(chr(13), " ").replace(chr(10), " ")}'
            )
            playlist_lines.append(source)

    append_items(
        live_categories_data,
        live_streams_data,
        "stream_id",
        "live",
        default_name="Canal sem nome",
        logo_key="stream_icon",
    )
    append_items(
        movie_categories_data,
        movie_streams_data,
        "stream_id",
        "movie",
        category_prefix="Filmes / ",
        default_name="Filme sem nome",
        logo_key="stream_icon",
    )

    if len(playlist_lines) == 1:
        raise PlaylistImportError("A API Xtream não retornou canais nem filmes para esta assinatura.")
    return "\n".join(playlist_lines)


def parse_playlist(content: str) -> list[PlaylistChannel]:
    if not content.lstrip("\ufeff\r\n ").startswith("#EXTM3U"):
        raise PlaylistImportError("O endereço não retornou uma playlist M3U válida.")

    channels: list[PlaylistChannel] = []
    seen_sources: set[str] = set()
    metadata: dict[str, str] | None = None

    for raw_line in content.splitlines():
        line = raw_line.strip()
        if line.startswith("#EXTINF:"):
            attributes = dict(ATTRIBUTE_PATTERN.findall(line))
            display_name = line.rsplit(",", 1)[-1].strip() if "," in line else ""
            metadata = {
                "name": display_name or attributes.get("tvg-name", "Canal sem nome"),
                "category": attributes.get("group-title", "Importados") or "Importados",
                "logo": attributes.get("tvg-logo", ""),
            }
            continue

        if not line or line.startswith("#") or metadata is None:
            continue
        parsed = urlparse(line)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or line in seen_sources:
            metadata = None
            continue
        channels.append(
            PlaylistChannel(
                name=metadata["name"][:120],
                category=metadata["category"][:80],
                logo=metadata["logo"] if metadata["logo"].startswith(("http://", "https://")) else "",
                source=line,
            )
        )
        seen_sources.add(line)
        metadata = None
        if len(channels) >= MAX_CHANNELS:
            break

    if not channels:
        raise PlaylistImportError("Nenhum canal HTTP ou HTTPS foi encontrado na playlist.")
    return channels
