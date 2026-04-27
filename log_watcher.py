"""
Überwacht die Minecraft latest.log (oder eine angegebene Datei) und erkennt
bekannte Doomsday-/Injection-Muster: lange Serien von Zeitstempel + Ziffer 1–8.
Bei Treffer wird eine Zeile in die Ausgabe-Logdatei geschrieben.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import time
from pathlib import Path

# Mindestanzahl aufeinanderfolgender „Spam“-Zeilen (ein Zyklus 1–8 = 8 Zeilen).
MIN_SUSPICIOUS_STREAK = 16

# Nach einer Meldung: Pause, bevor erneut gemeldet wird (Sekunden).
COOLDOWN_SEC = 10.0

# Zeile: Uhrzeit mit Millisekunden, dann Whitespace, dann genau eine Ziffer 1–8.
SPAM_LINE = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3}\s+[1-8]\s*$")
DIGIT_ONLY = re.compile(r"^[1-8]\s*$")


def _mc_style_line_ends_single_digit(raw: str) -> bool:
    t = raw.strip()
    if not t.startswith("["):
        return False
    colon = t.rfind(":")
    if colon < 0 or colon >= len(t) - 1:
        return False
    tail = t[colon + 1 :].strip()
    return bool(DIGIT_ONLY.match(tail))


def _instant_signal(raw: str) -> bool:
    if "[STATS_SERVICE]" in raw and "Report crash" in raw:
        if "Not real" in raw or "Throwable not set" in raw:
            return True
    t = raw.strip()
    if t.lower() == "readed" or t == "0000":
        return True
    return False


def _is_digit_spam_line(raw: str) -> bool:
    t = raw.strip()
    if SPAM_LINE.match(t) or DIGIT_ONLY.match(t):
        return True
    return _mc_style_line_ends_single_digit(raw)


def default_latest_log() -> Path:
    appdata = os.environ.get("APPDATA", "")
    if appdata:
        return Path(appdata) / ".minecraft" / "logs" / "latest.log"
    return Path.home() / ".minecraft" / "logs" / "latest.log"


def default_output_path(watched: Path) -> Path:
    return watched.parent / "anticheat-client.log"


def append_detection(output: Path, message: str = "cheat erkannt") -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("a", encoding="utf-8", errors="replace") as f:
        f.write(f"{message}\n")


def process_line(
    line: str,
    streak: int,
    last_alert: float,
    now: float,
    output: Path,
) -> tuple[int, float]:
    raw = line.rstrip("\r\n")
    if _instant_signal(raw) and (now - last_alert) >= COOLDOWN_SEC:
        append_detection(output)
        return 0, now

    if _is_digit_spam_line(raw):
        streak += 1
    else:
        streak = 0

    if streak >= MIN_SUSPICIOUS_STREAK and (now - last_alert) >= COOLDOWN_SEC:
        append_detection(output)
        return 0, now

    return streak, last_alert


def scan_file(path: Path, output: Path) -> bool:
    """Einmaliger Durchlauf; gibt True zurück, wenn mindestens ein Treffer."""
    streak = 0
    last_alert = -COOLDOWN_SEC
    triggered = False
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            now = time.monotonic()
            new_streak, new_alert = process_line(line, streak, last_alert, now, output)
            if new_alert != last_alert:
                triggered = True
            streak, last_alert = new_streak, new_alert
    return triggered


def tail_loop(watched: Path, output: Path, poll_sec: float = 0.5) -> None:
    streak = 0
    last_alert = -COOLDOWN_SEC
    pending = b""

    while not watched.exists():
        time.sleep(poll_sec)

    with watched.open("rb") as f:
        f.seek(0, os.SEEK_END)
        while True:
            now = time.monotonic()
            chunk = f.read()
            if chunk:
                pending += chunk
                while b"\n" in pending:
                    raw_line, pending = pending.split(b"\n", 1)
                    line = raw_line.decode("utf-8", errors="replace") + "\n"
                    streak, last_alert = process_line(
                        line, streak, last_alert, now, output
                    )
            else:
                time.sleep(poll_sec)


def main() -> int:
    p = argparse.ArgumentParser(description="Minecraft-Log-Watcher (Doomsday-Muster)")
    p.add_argument(
        "--log",
        type=Path,
        default=None,
        help="Zu überwachende Logdatei (Standard: %%APPDATA%%\\.minecraft\\logs\\latest.log)",
    )
    p.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Ausgabe-Log mit Meldungen (Standard: <logs>/anticheat-client.log)",
    )
    p.add_argument(
        "--once",
        action="store_true",
        help="Datei nur einmal einlesen und beenden (Test)",
    )
    p.add_argument(
        "--poll",
        type=float,
        default=0.5,
        help="Pause in Sekunden, wenn keine neuen Bytes (nur Live-Modus)",
    )
    args = p.parse_args()

    watched = args.log or default_latest_log()
    output = args.out or default_output_path(watched)

    if args.once:
        if not watched.is_file():
            print(f"Datei nicht gefunden: {watched}", file=sys.stderr)
            return 1
        if scan_file(watched, output):
            print(f"Meldung geschrieben nach: {output}")
        else:
            print("Kein bekanntes Muster gefunden.")
        return 0

    print(f"Überwache: {watched}")
    print(f"Meldungen nach: {output}")
    print("Beenden mit Strg+C.")
    try:
        tail_loop(watched, output, poll_sec=args.poll)
    except KeyboardInterrupt:
        print("\nBeendet.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
