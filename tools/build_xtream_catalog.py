"""Create a catalog JSON from the desktop database and an Xtream Codes account.

Credentials are read only from environment variables and are never printed.
The JSON output is temporary and must be encrypted with EncryptCatalog immediately.
"""

from __future__ import annotations

import json
import os
import sqlite3
import sys
import time
from pathlib import Path
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


def fetch_json(url: str) -> object:
    request = Request(url, headers={"User-Agent": "IPTV-Caseiro/1.0", "Accept": "application/json"})
    with urlopen(request, timeout=60) as response:
        if response.status != 200:
            raise RuntimeError(f"A API respondeu com o código {response.status}.")
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("Use: build_xtream_catalog.py <banco.db> <host> <catalog.json>")
    username = os.environ.get("IPTV_PROVIDER_USER", "")
    password = os.environ.get("IPTV_PROVIDER_PASSWORD", "")
    if not username or not password:
        raise SystemExit("Defina IPTV_PROVIDER_USER e IPTV_PROVIDER_PASSWORD no ambiente.")

    database_path, host, output_path = sys.argv[1], sys.argv[2].rstrip("/"), Path(sys.argv[3])
    now = int(time.time() * 1000)
    channels: list[dict[str, object]] = []
    seen: set[str] = set()

    connection = sqlite3.connect(database_path)
    connection.row_factory = sqlite3.Row
    try:
        rows = connection.execute(
            "SELECT nome, descricao, categoria, logo, tipo, fonte, ativo, favorito FROM canais ORDER BY id"
        ).fetchall()
    finally:
        connection.close()
    source_types = {"stream": "STREAM", "externo": "EXTERNAL", "arquivo": "LOCAL"}
    for row in rows:
        source = (row["fonte"] or "").strip()
        if not source or source in seen or row["tipo"] not in source_types:
            continue
        seen.add(source)
        channels.append(
            {
                "name": row["nome"],
                "description": row["descricao"],
                "category": row["categoria"],
                "logoUrl": row["logo"],
                "sourceType": source_types[row["tipo"]],
                "source": source,
                "active": bool(row["ativo"]) and row["tipo"] != "arquivo",
                "favorite": bool(row["favorito"]),
                "createdAt": now,
            }
        )

    api = f"{host}/player_api.php?" + urlencode({"username": username, "password": password})
    categories_data = fetch_json(api + "&action=get_live_categories")
    streams_data = fetch_json(api + "&action=get_live_streams")
    if not isinstance(categories_data, list) or not isinstance(streams_data, list):
        raise RuntimeError("A API não retornou a lista de canais esperada.")
    categories = {
        str(item.get("category_id", "")): str(item.get("category_name", "Sem categoria")).strip()
        for item in categories_data
        if isinstance(item, dict)
    }
    provider_added = 0
    telecine_added = 0
    safe_user = quote(username, safe="")
    safe_password = quote(password, safe="")
    for item in streams_data:
        if not isinstance(item, dict) or item.get("stream_id") is None:
            continue
        extension = str(item.get("container_extension") or "ts")
        extension = "".join(character for character in extension if character.isalnum()) or "ts"
        source = f"{host}/live/{safe_user}/{safe_password}/{item['stream_id']}.{extension}"
        if source in seen:
            continue
        seen.add(source)
        category = categories.get(str(item.get("category_id", "")), "Sem categoria") or "Sem categoria"
        channels.append(
            {
                "name": str(item.get("name") or "Canal sem nome").strip(),
                "description": "",
                "category": category,
                "logoUrl": str(item.get("stream_icon") or "").strip(),
                "sourceType": "STREAM",
                "source": source,
                "active": True,
                "favorite": False,
                "createdAt": now,
            }
        )
        provider_added += 1
        if "telecine" in category.lower() or "telecine" in str(item.get("name") or "").lower():
            telecine_added += 1

    output_path.write_text(
        json.dumps({"format": 1, "channels": channels}, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"catálogo: {len(channels)}; API: {provider_added}; Telecine: {telecine_added}")


if __name__ == "__main__":
    main()
