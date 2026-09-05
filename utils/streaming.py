"""Utilitários reservados para recursos futuros de streaming e FFmpeg."""

from __future__ import annotations

import shutil


def ffmpeg_disponivel() -> bool:
    """Retorna True quando o executável FFmpeg está no PATH."""
    return shutil.which("ffmpeg") is not None
