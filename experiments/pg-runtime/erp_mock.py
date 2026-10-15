"""Independent persistent HTTP ERP emulator. POC only; not a production ERP contract."""
import json
import sqlite3
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def connect(self):
        conn = sqlite3.connect(self.server.db_path, timeout=5)
        conn.execute("PRAGMA busy_timeout=5000")
        return conn

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"ok": True})
        if self.path == "/stats":
            with self.connect() as db:
                count = db.execute("SELECT count(*) FROM receipt").fetchone()[0]
            return self.reply(200, {"unique_writes": count})
        if self.path.startswith("/receipts/"):
            key = unquote(self.path.split("/receipts/", 1)[1])
            with self.connect() as db:
                row = db.execute("SELECT lot, payload_hash FROM receipt WHERE idem_key=?", (key,)).fetchone()
            return self.reply(200, {"state": "CONFIRMED", "lot": row[0], "payload_hash": row[1]}) if row else self.reply(404, {"state": "NOT_FOUND"})
        return self.reply(404, {"error": "UNKNOWN_PATH"})

    def do_POST(self):
        if self.path != "/hold":
            return self.reply(404, {"error": "UNKNOWN_PATH"})
        size = int(self.headers.get("Content-Length", 0))
        if size > 10240:
            return self.reply(413, {"error": "LARGE_PAYLOAD"})
        try:
            payload = json.loads(self.rfile.read(size))
            key, lot, digest = payload["key"], payload["lot"], payload["payload_hash"]
            if not all(isinstance(s, str) and s for s in (key, lot, digest)):
                raise ValueError("bad request")
        except (ValueError, KeyError, TypeError):
            return self.reply(400, {"error": "INVALID_PAYLOAD"})
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT lot,payload_hash FROM receipt WHERE idem_key=?", (key,)).fetchone()
            if existing and existing != (lot, digest):
                return self.reply(409, {"error": "IDEMPOTENCY_CONFLICT"})
            if not existing:
                db.execute("INSERT INTO receipt(idem_key,lot,payload_hash) VALUES(?,?,?)", (key, lot, digest))
            db.commit()
        self.reply(200, {"state": "CONFIRMED", "lot": lot, "payload_hash": digest})


def main():
    db_path, port = sys.argv[1], int(sys.argv[2])
    with sqlite3.connect(db_path) as db:
        db.execute("CREATE TABLE IF NOT EXISTS receipt(idem_key TEXT PRIMARY KEY,lot TEXT NOT NULL,payload_hash TEXT NOT NULL)")
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.db_path = db_path
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
