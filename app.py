from __future__ import annotations

import ipaddress
import base64
import json
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import unquote, urlencode, urljoin, urlparse, urlsplit
from urllib.request import Request, urlopen

from flask import (
    Flask,
    Response,
    abort,
    flash,
    redirect,
    render_template,
    request,
    send_from_directory,
    jsonify,
    session,
    url_for,
)
from werkzeug.utils import secure_filename

from utils.m3u import PlaylistImportError, fetch_playlist, parse_playlist


BASE_DIR = Path(__file__).resolve().parent
DATABASE_DIR = BASE_DIR / "database"
DATABASE_PATH = DATABASE_DIR / "banco.db"
VIDEO_DIR = BASE_DIR / "videos"
PLAYLIST_DIR = BASE_DIR / "playlists"
PLAYLIST_CACHE_PATH = PLAYLIST_DIR / "br.m3u"
DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/br.m3u"
ALLOWED_VIDEO_EXTENSIONS = {".mp4", ".webm", ".ogg", ".m4v"}
ALLOWED_RECORDING_EXTENSIONS = {".ts", ".mp4", ".mkv", ".webm"}

recording_lock = threading.Lock()
recording_process: subprocess.Popen[bytes] | None = None
recording_channel_id: int | None = None
recording_filename: str | None = None

app = Flask(__name__)
app.config.update(
    SECRET_KEY="iptv-caseiro-desenvolvimento-local",
    MAX_CONTENT_LENGTH=16 * 1024 * 1024,
)


def ensure_directories() -> None:
    for directory in (DATABASE_DIR, VIDEO_DIR, PLAYLIST_DIR):
        directory.mkdir(parents=True, exist_ok=True)
    recordings_dir().mkdir(parents=True, exist_ok=True)


def recordings_dir() -> Path:
    return VIDEO_DIR / "gravacoes"


def get_db() -> sqlite3.Connection:
    connection = sqlite3.connect(DATABASE_PATH)
    connection.row_factory = sqlite3.Row
    return connection


@contextmanager
def database_connection():
    connection = get_db()
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def init_db() -> None:
    ensure_directories()
    with database_connection() as database:
        database.execute(
            """
            CREATE TABLE IF NOT EXISTS canais (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nome TEXT NOT NULL,
                descricao TEXT NOT NULL DEFAULT '',
                categoria TEXT NOT NULL DEFAULT 'Geral',
                logo TEXT NOT NULL DEFAULT '',
                tipo TEXT NOT NULL CHECK (tipo IN ('arquivo', 'stream', 'externo')),
                fonte TEXT NOT NULL,
                ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
                favorito INTEGER NOT NULL DEFAULT 0 CHECK (favorito IN (0, 1)),
                criado_em TEXT NOT NULL
            )
            """
        )
        table_sql = database.execute(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'canais'"
        ).fetchone()["sql"]
        if "'externo'" not in table_sql:
            legacy_columns = {
                row["name"]
                for row in database.execute("PRAGMA table_info(canais)").fetchall()
            }
            favorite_expression = "favorito" if "favorito" in legacy_columns else "0"
            database.execute("ALTER TABLE canais RENAME TO canais_anterior")
            database.execute(
                """
                CREATE TABLE canais (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nome TEXT NOT NULL,
                    descricao TEXT NOT NULL DEFAULT '',
                    categoria TEXT NOT NULL DEFAULT 'Geral',
                    logo TEXT NOT NULL DEFAULT '',
                    tipo TEXT NOT NULL CHECK (tipo IN ('arquivo', 'stream', 'externo')),
                    fonte TEXT NOT NULL,
                    ativo INTEGER NOT NULL DEFAULT 1 CHECK (ativo IN (0, 1)),
                    favorito INTEGER NOT NULL DEFAULT 0 CHECK (favorito IN (0, 1)),
                    criado_em TEXT NOT NULL
                )
                """
            )
            database.execute(
                f"""
                INSERT INTO canais
                    (id, nome, descricao, categoria, logo, tipo, fonte, ativo, favorito, criado_em)
                SELECT id, nome, descricao, categoria, logo, tipo, fonte, ativo,
                    {favorite_expression}, criado_em
                FROM canais_anterior
                """
            )
            database.execute("DROP TABLE canais_anterior")
        columns = {
            row["name"] for row in database.execute("PRAGMA table_info(canais)").fetchall()
        }
        if "favorito" not in columns:
            database.execute(
                "ALTER TABLE canais ADD COLUMN favorito INTEGER NOT NULL DEFAULT 0 "
                "CHECK (favorito IN (0, 1))"
            )
        count = database.execute("SELECT COUNT(*) FROM canais").fetchone()[0]
        if count == 0:
            database.execute(
                """
                INSERT INTO canais
                    (nome, descricao, categoria, logo, tipo, fonte, ativo, criado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    "Canal de Teste",
                    "Vídeo de demonstração local em domínio público (CC0).",
                    "Meus vídeos",
                    "",
                    "arquivo",
                    "video-teste.mp4",
                    1,
                    datetime.now(timezone.utc).isoformat(),
                ),
            )
        database.execute(
            """
            UPDATE canais SET descricao = ?
            WHERE nome = ? AND tipo = ? AND fonte = ?
                AND descricao = ?
            """,
            (
                "Vídeo de demonstração local em domínio público (CC0).",
                "Canal de Teste",
                "arquivo",
                "video-teste.mp4",
                "Adicione video-teste.mp4 na pasta videos para começar.",
            ),
        )


def get_channel(channel_id: int, active_only: bool = False) -> sqlite3.Row:
    query = "SELECT * FROM canais WHERE id = ?"
    parameters: tuple[object, ...] = (channel_id,)
    if active_only:
        query += " AND ativo = 1"
    with database_connection() as database:
        channel = database.execute(query, parameters).fetchone()
    if channel is None:
        abort(404)
    return channel


def validate_url(value: str) -> bool:
    try:
        parsed = urlparse(value)
        return parsed.scheme in {"http", "https"} and bool(parsed.netloc)
    except ValueError:
        return False


def validate_channel_form(form: dict[str, str]) -> tuple[dict[str, object], str | None]:
    data: dict[str, object] = {
        "nome": form.get("nome", "").strip(),
        "descricao": form.get("descricao", "").strip(),
        "categoria": form.get("categoria", "").strip() or "Geral",
        "logo": form.get("logo", "").strip(),
        "tipo": form.get("tipo", "").strip(),
        "fonte": form.get("fonte", "").strip(),
        "ativo": 1 if form.get("ativo") == "on" else 0,
    }

    if not data["nome"] or not data["fonte"]:
        return data, "Nome e fonte são obrigatórios."
    if data["tipo"] not in {"arquivo", "stream", "externo"}:
        return data, "O tipo selecionado é inválido."
    if data["tipo"] == "arquivo":
        filename = str(data["fonte"])
        safe_name = secure_filename(filename)
        if safe_name != filename or Path(filename).suffix.lower() not in ALLOWED_VIDEO_EXTENSIONS:
            return data, "Use apenas o nome de um arquivo de vídeo válido, sem pastas."
    elif not validate_url(str(data["fonte"])):
        return data, "Informe uma URL HTTP ou HTTPS válida."
    if data["logo"] and not validate_url(str(data["logo"])):
        return data, "A logo deve ser uma URL HTTP ou HTTPS válida."
    return data, None


def channel_source(channel: sqlite3.Row, external: bool = False) -> str:
    if channel["tipo"] == "arquivo":
        return url_for("serve_video", filename=channel["fonte"], _external=external)
    return channel["fonte"]


def get_recording_status() -> dict[str, object] | None:
    global recording_process, recording_channel_id, recording_filename
    with recording_lock:
        if recording_process is not None and recording_process.poll() is not None:
            recording_process = None
            recording_channel_id = None
            recording_filename = None
        if recording_process is None:
            return None
        return {
            "channel_id": recording_channel_id,
            "filename": recording_filename,
        }


def start_recording(channel: sqlite3.Row) -> str:
    global recording_process, recording_channel_id, recording_filename
    if channel["tipo"] != "stream" or not validate_url(channel["fonte"]):
        raise ValueError("A gravação está disponível apenas para streams HTTP ou HTTPS.")
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise ValueError("Instale o FFmpeg para gravar no programa local.")

    safe_name = secure_filename(channel["nome"]) or "canal"
    filename = f"{safe_name}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.ts"
    destination = recordings_dir() / filename
    creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    command = [
        ffmpeg,
        "-nostdin",
        "-hide_banner",
        "-loglevel", "error",
        "-y",
        "-i", channel["fonte"],
        "-map", "0",
        "-c", "copy",
        "-f", "mpegts",
        str(destination),
    ]
    with recording_lock:
        if recording_process is not None and recording_process.poll() is None:
            raise ValueError("Já existe uma gravação em andamento.")
        recordings_dir().mkdir(parents=True, exist_ok=True)
        recording_process = subprocess.Popen(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creation_flags,
        )
        recording_channel_id = channel["id"]
        recording_filename = filename
    return filename


def stop_recording() -> str | None:
    global recording_process, recording_channel_id, recording_filename
    with recording_lock:
        process = recording_process
        filename = recording_filename
        if process is None or process.poll() is not None:
            recording_process = None
            recording_channel_id = None
            recording_filename = None
            return None
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
        recording_process = None
        recording_channel_id = None
        recording_filename = None
        return filename


def parse_xtream_live_source(source: str) -> tuple[str, str, str, str] | None:
    try:
        parsed = urlsplit(source)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            return None
        segments = [segment for segment in parsed.path.split("/") if segment]
        live_index = next(
            (index for index in range(len(segments) - 1, -1, -1) if segments[index].lower() == "live"),
            -1,
        )
        if live_index < 0 or len(segments) < live_index + 4:
            return None
        username = unquote(segments[live_index + 1])
        password = unquote(segments[live_index + 2])
        stream_id = unquote(segments[live_index + 3]).rsplit(".", 1)[0]
        if not username or not password or not stream_id:
            return None
        prefix = "/".join(segments[:live_index])
        root = f"{parsed.scheme}://{parsed.netloc}" + (f"/{prefix}" if prefix else "")
        return root, username, password, stream_id
    except ValueError:
        return None


def short_epg(source: str) -> dict[str, object]:
    access = parse_xtream_live_source(source)
    if access is None:
        return {"available": False}
    root, username, password, stream_id = access
    query = urlencode({
        "username": username,
        "password": password,
        "action": "get_short_epg",
        "stream_id": stream_id,
        "limit": "6",
    })
    try:
        request_epg = Request(
            f"{root}/player_api.php?{query}",
            headers={"Accept": "application/json", "User-Agent": "IPTV-Caseiro/1.0"},
        )
        with urlopen(request_epg, timeout=15) as response:
            data = json.loads(response.read().decode("utf-8"))
        programs = []
        for listing in data.get("epg_listings", []):
            start = int(listing.get("start_timestamp") or 0)
            end = int(listing.get("stop_timestamp") or 0)
            if start <= 0 or end <= start:
                continue
            encoded_title = str(listing.get("title") or "")
            try:
                title = base64.b64decode(encoded_title + "===").decode("utf-8").strip()
            except (UnicodeDecodeError, ValueError):
                title = encoded_title.strip()
            programs.append({"title": title or "Programa sem título", "start": start, "end": end})
        programs.sort(key=lambda item: int(item["start"]))
        now = int(time.time())
        current = next((item for item in reversed(programs) if item["start"] <= now < item["end"]), None)
        next_program = next((item for item in programs if item["start"] >= (current["end"] if current else now)), None)
        return {"available": bool(current or next_program), "current": current, "next": next_program}
    except (OSError, ValueError, json.JSONDecodeError):
        return {"available": False}


@app.context_processor
def inject_environment() -> dict[str, object]:
    return {"ffmpeg_available": shutil.which("ffmpeg") is not None}


@app.get("/canais")
@app.get("/catalogo")
def catalog_alias():
    return redirect(url_for("index"))


@app.get("/playlists")
def playlists_alias():
    return redirect(url_for("import_playlist"))


@app.get("/")
def index():
    selected_category = request.args.get("categoria", "").strip()
    search = request.args.get("q", "").strip()
    favorites_only = request.args.get("favoritos") == "1"
    if selected_category:
        session["last_viewed_category"] = selected_category
    query = "SELECT * FROM canais WHERE ativo = 1"
    parameters: list[object] = []
    if favorites_only:
        query += " AND favorito = 1"
    if selected_category:
        query += " AND categoria = ?"
        parameters.append(selected_category)
    if search:
        query += " AND (nome LIKE ? OR descricao LIKE ? OR categoria LIKE ?)"
        term = f"%{search}%"
        parameters.extend((term, term, term))
    query += " ORDER BY categoria, nome"

    with database_connection() as database:
        channels = database.execute(query, parameters).fetchall()
        category_rows = database.execute(
            "SELECT DISTINCT categoria FROM canais WHERE ativo = 1 ORDER BY categoria"
        ).fetchall()
        favorite_count = database.execute(
            "SELECT COUNT(*) FROM canais WHERE ativo = 1 AND favorito = 1"
        ).fetchone()[0]
    favorite_channels = [channel for channel in channels if channel["favorito"]]
    other_channels = [channel for channel in channels if not channel["favorito"]]
    categories = [row["categoria"] for row in category_rows]
    recent_category = session.get("last_viewed_category", "")
    if recent_category in categories:
        categories.remove(recent_category)
        categories.insert(0, recent_category)
    return render_template(
        "index.html",
        channels=channels,
        favorite_channels=favorite_channels,
        other_channels=other_channels,
        categories=categories,
        favorite_count=favorite_count,
        favorites_only=favorites_only,
        selected_category=selected_category,
        search=search,
    )


@app.get("/player/<int:channel_id>")
def player(channel_id: int):
    channel = get_channel(channel_id, active_only=True)
    if channel["tipo"] == "externo":
        return redirect(url_for("open_external_channel", channel_id=channel_id))
    source = channel_source(channel)
    source_available = channel["tipo"] != "arquivo" or (VIDEO_DIR / channel["fonte"]).is_file()
    with database_connection() as database:
        active_channels = database.execute(
            "SELECT id, nome FROM canais WHERE ativo = 1 AND tipo != 'externo' "
            "ORDER BY categoria, nome"
        ).fetchall()
    current_index = next(
        (index for index, item in enumerate(active_channels) if item["id"] == channel_id),
        0,
    )
    previous_channel = active_channels[current_index - 1] if len(active_channels) > 1 else None
    next_channel = (
        active_channels[(current_index + 1) % len(active_channels)]
        if len(active_channels) > 1
        else None
    )
    return render_template(
        "player.html",
        channel=channel,
        source=source,
        source_available=source_available,
        previous_channel=previous_channel,
        next_channel=next_channel,
        recording=get_recording_status(),
        recording_supported=channel["tipo"] == "stream" and shutil.which("ffmpeg") is not None,
    )


@app.get("/canal/<int:channel_id>/abrir")
def open_external_channel(channel_id: int):
    channel = get_channel(channel_id, active_only=True)
    if channel["tipo"] != "externo" or not validate_url(channel["fonte"]):
        abort(404)
    return redirect(channel["fonte"], code=302)


@app.post("/player/<int:channel_id>/favorito")
def toggle_favorite(channel_id: int):
    channel = get_channel(channel_id)
    with database_connection() as database:
        database.execute(
            "UPDATE canais SET favorito = CASE favorito WHEN 1 THEN 0 ELSE 1 END WHERE id = ?",
            (channel_id,),
        )
    removing_favorite = bool(channel["favorito"])
    flash(
        "Canal removido dos favoritos. Agora ele pode ser excluído."
        if removing_favorite
        else "Canal adicionado aos favoritos.",
        "success",
    )

    origin = request.form.get("origem")
    if origin == "admin":
        return redirect(url_for("admin"))
    if origin == "catalogo":
        arguments = {
            key: request.form.get(key, "").strip()
            for key in ("categoria", "q", "favoritos")
            if request.form.get(key, "").strip()
        }
        return redirect(url_for("index", **arguments))
    return redirect(url_for("player", channel_id=channel_id))


@app.post("/player/<int:channel_id>/gravar")
def record_channel(channel_id: int):
    channel = get_channel(channel_id, active_only=True)
    try:
        filename = start_recording(channel)
        flash(f"Gravação iniciada: {filename}", "success")
    except ValueError as error:
        flash(str(error), "danger")
    return redirect(url_for("player", channel_id=channel_id))


@app.post("/player/<int:channel_id>/gravar/parar")
def stop_channel_recording(channel_id: int):
    filename = stop_recording()
    if filename:
        flash(f"Gravação salva em videos/gravacoes/{filename}", "success")
    else:
        flash("Não havia gravação em andamento.", "warning")
    return redirect(url_for("player", channel_id=channel_id))


@app.get("/player/<int:channel_id>/epg")
def channel_epg(channel_id: int):
    channel = get_channel(channel_id, active_only=True)
    if channel["tipo"] != "stream":
        return jsonify({"available": False})
    return jsonify(short_epg(channel["fonte"]))


@app.get("/videos/<path:filename>")
def serve_video(filename: str):
    safe_name = secure_filename(filename)
    if safe_name != filename or Path(filename).suffix.lower() not in ALLOWED_VIDEO_EXTENSIONS:
        abort(404)
    return send_from_directory(VIDEO_DIR, safe_name, conditional=True)


@app.get("/gravacoes/<path:filename>")
def serve_recording(filename: str):
    safe_name = secure_filename(filename)
    if safe_name != filename or Path(filename).suffix.lower() not in ALLOWED_RECORDING_EXTENSIONS:
        abort(404)
    return send_from_directory(recordings_dir(), safe_name, conditional=True)


@app.route("/admin", methods=["GET", "POST"])
def admin():
    if request.method == "POST":
        data, error = validate_channel_form(request.form)
        if error:
            flash(error, "danger")
        else:
            with database_connection() as database:
                database.execute(
                    """
                    INSERT INTO canais
                        (nome, descricao, categoria, logo, tipo, fonte, ativo, criado_em)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (*data.values(), datetime.now(timezone.utc).isoformat()),
                )
            flash("Canal cadastrado com sucesso.", "success")
            return redirect(url_for("admin"))

    with database_connection() as database:
        channels = database.execute("SELECT * FROM canais ORDER BY criado_em DESC").fetchall()
        categories = database.execute("SELECT DISTINCT categoria FROM canais ORDER BY categoria").fetchall()
    return render_template("admin.html", channels=channels, categories=categories, editing=None)


@app.route("/admin/editar/<int:channel_id>", methods=["GET", "POST"])
def edit_channel(channel_id: int):
    channel = get_channel(channel_id)
    if request.method == "POST":
        data, error = validate_channel_form(request.form)
        if error:
            flash(error, "danger")
        else:
            with database_connection() as database:
                database.execute(
                    """
                    UPDATE canais SET nome = ?, descricao = ?, categoria = ?, logo = ?,
                        tipo = ?, fonte = ?, ativo = ? WHERE id = ?
                    """,
                    (*data.values(), channel_id),
                )
            flash("Canal atualizado.", "success")
            return redirect(url_for("admin"))

    with database_connection() as database:
        channels = database.execute("SELECT * FROM canais ORDER BY criado_em DESC").fetchall()
        categories = database.execute("SELECT DISTINCT categoria FROM canais ORDER BY categoria").fetchall()
    return render_template("admin.html", channels=channels, categories=categories, editing=channel)


@app.post("/admin/alternar/<int:channel_id>")
def toggle_channel(channel_id: int):
    get_channel(channel_id)
    with database_connection() as database:
        database.execute("UPDATE canais SET ativo = CASE ativo WHEN 1 THEN 0 ELSE 1 END WHERE id = ?", (channel_id,))
    flash("Status do canal atualizado.", "success")
    return redirect(url_for("admin"))


@app.post("/admin/excluir/<int:channel_id>")
def delete_channel(channel_id: int):
    channel = get_channel(channel_id)
    if channel["favorito"]:
        flash("Remova o canal dos favoritos antes de excluí-lo.", "danger")
        return redirect(url_for("admin"))
    with database_connection() as database:
        database.execute("DELETE FROM canais WHERE id = ?", (channel_id,))
    flash("Canal excluído.", "success")
    return redirect(url_for("admin"))


@app.post("/admin/excluir-varios")
def delete_multiple_channels():
    selected_ids = sorted(
        {int(value) for value in request.form.getlist("selected") if value.isdigit()}
    )
    if not selected_ids:
        flash("Selecione pelo menos um canal para excluir.", "danger")
        return redirect(url_for("admin"))

    placeholders = ",".join("?" for _ in selected_ids)
    with database_connection() as database:
        protected_count = database.execute(
            f"SELECT COUNT(*) FROM canais WHERE id IN ({placeholders}) AND favorito = 1",
            selected_ids,
        ).fetchone()[0]
        cursor = database.execute(
            f"DELETE FROM canais WHERE id IN ({placeholders}) AND favorito = 0",
            selected_ids,
        )
        deleted_count = cursor.rowcount
    message = f"{deleted_count} canal(is) excluído(s)."
    if protected_count:
        message += f" {protected_count} favorito(s) preservado(s)."
    flash(message, "success")
    return redirect(url_for("admin"))


@app.post("/admin/pasta/excluir")
def delete_folder():
    category = request.form.get("categoria", "").strip()
    if not category:
        flash("Escolha uma pasta para excluir.", "danger")
        return redirect(url_for("admin"))
    with database_connection() as database:
        cursor = database.execute("DELETE FROM canais WHERE categoria = ?", (category,))
        deleted_count = cursor.rowcount
    if session.get("last_viewed_category") == category:
        session.pop("last_viewed_category", None)
    flash(f"Pasta {category} excluída com {deleted_count} item(ns).", "success")
    return redirect(url_for("admin"))


@app.route("/admin/importar", methods=["GET", "POST"])
def import_playlist():
    source_url = request.form.get("url", DEFAULT_PLAYLIST_URL).strip()
    channels = []

    if request.method == "POST":
        try:
            playlist_content = fetch_playlist(source_url)
        except PlaylistImportError as error:
            if source_url == DEFAULT_PLAYLIST_URL and PLAYLIST_CACHE_PATH.is_file():
                playlist_content = PLAYLIST_CACHE_PATH.read_text(encoding="utf-8-sig")
                flash("Sem acesso à lista online. Usando a cópia local incluída no projeto.", "warning")
            else:
                flash(str(error), "danger")
                return render_template("import_m3u.html", channels=[], source_url=source_url)

        try:
            channels = parse_playlist(playlist_content)
        except PlaylistImportError as error:
            flash(str(error), "danger")
            return render_template("import_m3u.html", channels=[], source_url=source_url)

        if request.form.get("action") == "import":
            selected_indexes = {
                int(value) for value in request.form.getlist("selected") if value.isdigit()
            }
            selected = [channel for index, channel in enumerate(channels) if index in selected_indexes]
            if not selected:
                flash("Selecione pelo menos um canal para importar.", "danger")
            else:
                imported = 0
                with database_connection() as database:
                    if request.form.get("replace_existing") == "1":
                        database.execute("DELETE FROM canais")
                    existing_sources = {
                        row["fonte"] for row in database.execute("SELECT fonte FROM canais").fetchall()
                    }
                    for channel in selected:
                        if channel.source in existing_sources:
                            continue
                        database.execute(
                            """
                            INSERT INTO canais
                                (nome, descricao, categoria, logo, tipo, fonte, ativo, criado_em)
                            VALUES (?, ?, ?, ?, 'stream', ?, 1, ?)
                            """,
                            (
                                channel.name,
                                "Importado de playlist M3U.",
                                channel.category,
                                channel.logo,
                                channel.source,
                                datetime.now(timezone.utc).isoformat(),
                            ),
                        )
                        existing_sources.add(channel.source)
                        imported += 1
                skipped = len(selected) - imported
                message = f"{imported} canal(is) importado(s)."
                if request.form.get("replace_existing") == "1":
                    message += " Catálogo anterior substituído."
                if skipped:
                    message += f" {skipped} duplicado(s) ignorado(s)."
                flash(message, "success")
                return redirect(url_for("admin"))

    return render_template("import_m3u.html", channels=channels, source_url=source_url)


@app.get("/playlist.m3u")
def playlist():
    with database_connection() as database:
        channels = database.execute(
            "SELECT * FROM canais WHERE ativo = 1 AND tipo != 'externo' "
            "ORDER BY categoria, nome"
        ).fetchall()
    lines = ["#EXTM3U"]
    for channel in channels:
        name = channel["nome"].replace("\n", " ").replace("\r", " ")
        category = channel["categoria"].replace('"', "'")
        logo = channel["logo"].replace('"', "'")
        lines.append(f'#EXTINF:-1 tvg-logo="{logo}" group-title="{category}",{name}')
        lines.append(channel_source(channel, external=True))
    return Response("\n".join(lines) + "\n", mimetype="audio/x-mpegurl")


def get_local_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        address = sock.getsockname()[0]
        ipaddress.ip_address(address)
        return address
    except (OSError, ValueError):
        try:
            return socket.gethostbyname(socket.gethostname())
        except socket.gaierror:
            return "IP_LOCAL"
    finally:
        sock.close()


if __name__ == "__main__":
    init_db()
    local_ip = get_local_ip()
    print("\nIPTV Caseiro iniciado.\n")
    print("Computador:\nhttp://localhost:5000\n")
    print(f"Outros dispositivos da rede:\nhttp://{local_ip}:5000\n")
    if not shutil.which("ffmpeg"):
        print("Aviso: FFmpeg não encontrado. Recursos avançados de vídeo ficarão indisponíveis.\n")
    app.run(host="0.0.0.0", port=5000, debug=True)
else:
    init_db()
