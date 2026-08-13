"""CivicSignal local backend: static site, reports API, uploads, and SQLite storage."""
from __future__ import annotations

import json
import mimetypes
import os
import re
import shutil
import smtplib
import sqlite3
import uuid
from datetime import datetime, timezone
from email.message import EmailMessage
from email.parser import BytesParser
from email.policy import default
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).parent.resolve()
UPLOADS = ROOT / "uploads"
DATABASE = ROOT / "civicsignal.db"
MAX_UPLOAD_BYTES = 10 * 1024 * 1024
ALLOWED_TYPES = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}


def db() -> sqlite3.Connection:
    connection = sqlite3.connect(DATABASE)
    connection.row_factory = sqlite3.Row
    return connection


def initialize_database() -> None:
    UPLOADS.mkdir(exist_ok=True)
    with db() as connection:
        connection.executescript("""
            CREATE TABLE IF NOT EXISTS reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                issue_type TEXT NOT NULL,
                location TEXT NOT NULL,
                description TEXT NOT NULL,
                image_url TEXT,
                created_at TEXT NOT NULL,
                municipal_status TEXT NOT NULL DEFAULT 'queued'
            );
            CREATE TABLE IF NOT EXISTS likes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS comments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
                author TEXT NOT NULL DEFAULT 'Neighbour',
                body TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
        """)
        if connection.execute("SELECT COUNT(*) FROM reports").fetchone()[0] == 0:
            now = datetime.now(timezone.utc).isoformat()
            seed = [
                ("Road damage", "Gandhi Nagar, Ward 12", "Large pothole causing traffic to swerve near the school gate", "assets/pothole-report.png", now, "sent"),
                ("Streetlight", "Aundh, Ward 8", "Three streetlights out on the walking path by the park", "assets/streetlight-report.png", now, "under review"),
                ("Footpath", "Kothrud, Ward 4", "Broken pavement tiles make this bus stop inaccessible", "assets/footpath-report.png", now, "needs attention"),
            ]
            connection.executemany("INSERT INTO reports (issue_type, location, description, image_url, created_at, municipal_status) VALUES (?, ?, ?, ?, ?, ?)", seed)


def serialize_report(connection: sqlite3.Connection, row: sqlite3.Row) -> dict:
    report_id = row["id"]
    comments = connection.execute("SELECT author, body, created_at FROM comments WHERE report_id = ? ORDER BY id ASC", (report_id,)).fetchall()
    likes = connection.execute("SELECT COUNT(*) FROM likes WHERE report_id = ?", (report_id,)).fetchone()[0]
    return {**dict(row), "likes": likes, "comments": [dict(comment) for comment in comments]}


def send_to_municipality(report: dict, image_path: Path | None) -> str:
    host, recipient = os.getenv("SMTP_HOST"), os.getenv("MUNICIPAL_EMAIL")
    if not host or not recipient:
        return "queued"
    message = EmailMessage()
    message["Subject"] = f"CivicSignal report: {report['issue_type']} — {report['location']}"
    message["From"] = os.getenv("SMTP_FROM", os.getenv("SMTP_USER", "civicsignal@localhost"))
    message["To"] = recipient
    message.set_content(f"New civic issue report\n\nType: {report['issue_type']}\nLocation: {report['location']}\n\nDescription:\n{report['description']}\n")
    if image_path and image_path.exists():
        message.add_attachment(image_path.read_bytes(), maintype="image", subtype=image_path.suffix.lstrip("."), filename=image_path.name)
    try:
        with smtplib.SMTP(host, int(os.getenv("SMTP_PORT", "587")), timeout=10) as smtp:
            if os.getenv("SMTP_TLS", "true").lower() != "false":
                smtp.starttls()
            if os.getenv("SMTP_USER"):
                smtp.login(os.getenv("SMTP_USER"), os.getenv("SMTP_PASSWORD", ""))
            smtp.send_message(message)
        return "sent"
    except OSError:
        return "queued"


class CivicHandler(SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args) -> None:
        print(f"[{self.log_date_time_string()}] {format % args}")

    def send_json(self, value: dict | list, status: int = 200) -> None:
        body = json.dumps(value).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def read_multipart(self) -> tuple[dict[str, str], tuple[str, str, bytes] | None]:
        length = int(self.headers.get("Content-Length", "0"))
        if length > MAX_UPLOAD_BYTES + 100_000:
            raise ValueError("Photo must be 10MB or smaller.")
        header = self.headers.get("Content-Type", "")
        raw = self.rfile.read(length)
        message = BytesParser(policy=default).parsebytes(f"Content-Type: {header}\r\nMIME-Version: 1.0\r\n\r\n".encode() + raw)
        fields, upload = {}, None
        for part in message.iter_parts():
            name = part.get_param("name", header="content-disposition")
            filename = part.get_filename()
            data = part.get_payload(decode=True) or b""
            if filename and name == "photo":
                upload = (filename, part.get_content_type(), data)
            elif name:
                fields[name] = data.decode("utf-8", "replace").strip()
        return fields, upload

    def do_GET(self) -> None:
        if self.path == "/api/reports":
            with db() as connection:
                rows = connection.execute("SELECT * FROM reports ORDER BY id DESC").fetchall()
                self.send_json([serialize_report(connection, row) for row in rows])
            return
        return super().do_GET()

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/api/reports":
            return self.create_report()
        match = re.fullmatch(r"/api/reports/(\d+)/(like|comments)", path)
        if not match:
            self.send_json({"error": "Not found"}, 404)
            return
        report_id, action = int(match.group(1)), match.group(2)
        if action == "like":
            with db() as connection:
                connection.execute("INSERT INTO likes (report_id, created_at) VALUES (?, ?)", (report_id, datetime.now(timezone.utc).isoformat()))
                connection.commit()
                count = connection.execute("SELECT COUNT(*) FROM likes WHERE report_id = ?", (report_id,)).fetchone()[0]
            self.send_json({"likes": count})
        else:
            try:
                payload = self.read_json()
                body = str(payload.get("body", "")).strip()[:500]
                if not body:
                    raise ValueError("Comment cannot be empty.")
                with db() as connection:
                    connection.execute("INSERT INTO comments (report_id, author, body, created_at) VALUES (?, ?, ?, ?)", (report_id, "Neighbour", body, datetime.now(timezone.utc).isoformat()))
                    connection.commit()
                self.send_json({"author": "Neighbour", "body": body}, 201)
            except (ValueError, json.JSONDecodeError) as error:
                self.send_json({"error": str(error)}, 400)

    def create_report(self) -> None:
        try:
            fields, upload = self.read_multipart()
            issue_type, location, description = (fields.get("issueType", ""), fields.get("location", ""), fields.get("description", ""))
            if not all((issue_type, location, description)):
                raise ValueError("Issue type, location, and description are required.")
            image_path, image_url = None, None
            if upload:
                filename, content_type, data = upload
                if content_type not in ALLOWED_TYPES or len(data) > MAX_UPLOAD_BYTES:
                    raise ValueError("Use a JPG, PNG, or WEBP image up to 10MB.")
                image_path = UPLOADS / f"{uuid.uuid4().hex}{ALLOWED_TYPES[content_type]}"
                image_path.write_bytes(data)
                image_url = f"uploads/{image_path.name}"
            now = datetime.now(timezone.utc).isoformat()
            with db() as connection:
                cursor = connection.execute("INSERT INTO reports (issue_type, location, description, image_url, created_at, municipal_status) VALUES (?, ?, ?, ?, ?, ?)", (issue_type, location, description, image_url, now, "queued"))
                report_id = cursor.lastrowid
                report = dict(connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone())
                status = send_to_municipality(report, image_path)
                connection.execute("UPDATE reports SET municipal_status = ? WHERE id = ?", (status, report_id))
                connection.commit()
                row = connection.execute("SELECT * FROM reports WHERE id = ?", (report_id,)).fetchone()
                self.send_json(serialize_report(connection, row), 201)
        except ValueError as error:
            self.send_json({"error": str(error)}, 400)


if __name__ == "__main__":
    initialize_database()
    port = int(os.getenv("PORT", "8000"))
    print(f"CivicSignal running at http://localhost:{port}")
    ThreadingHTTPServer(("", port), CivicHandler).serve_forever()
