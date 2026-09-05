from __future__ import annotations

import ipaddress
import re
import socket
from dataclasses import dataclass
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
MAX_CHANNELS = 500
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
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 443, type=socket.SOCK_STREAM)
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
    validate_public_url(url)
    request = Request(url, headers={"User-Agent": "IPTV-Caseiro/1.0"})
    opener = build_opener(PublicRedirectHandler())
    try:
        with opener.open(request, timeout=12) as response:
            validate_public_url(response.geturl())
            declared_size = response.headers.get("Content-Length")
            if declared_size and int(declared_size) > MAX_PLAYLIST_BYTES:
                raise PlaylistImportError("A playlist ultrapassa o limite de 2 MB.")
            content = response.read(MAX_PLAYLIST_BYTES + 1)
            if len(content) > MAX_PLAYLIST_BYTES:
                raise PlaylistImportError("A playlist ultrapassa o limite de 2 MB.")
            charset = response.headers.get_content_charset() or "utf-8-sig"
    except PlaylistImportError:
        raise
    except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
        raise PlaylistImportError("Não foi possível baixar a playlist. Verifique a URL e tente novamente.") from error

    try:
        return content.decode(charset)
    except (LookupError, UnicodeDecodeError):
        return content.decode("latin-1")


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
