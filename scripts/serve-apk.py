"""
YL-Harness mini APK download server.

Serves F:\\toolchain\\apk-share on port 8081 and auto-copies the freshly
built debug APK from this repo whenever it is newer, so the download link
always points at the latest build.

Start:  python scripts\\serve-apk.py     (from the repo root)
Links:  http://<this-pc-ip>:8081/                    (index page)
        http://<this-pc-ip>:8081/YL-Harness-debug.apk    (direct download)
"""
import io
import os
import shutil
import socket
import urllib.parse
from datetime import datetime
from http.server import HTTPServer, SimpleHTTPRequestHandler

SERVE_DIR = r"F:\toolchain\apk-share"
APK_SRC = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "build", "outputs", "apk", "debug", "app-debug.apk",
)
APK_NAME = "YL-Harness-debug.apk"
PORT = 8081

os.makedirs(SERVE_DIR, exist_ok=True)


def refresh_apk():
    """Copy the project's debug APK into the serve dir if it is newer."""
    src, dst = APK_SRC, os.path.join(SERVE_DIR, APK_NAME)
    if not os.path.isfile(src):
        return False
    if not os.path.isfile(dst) or os.path.getmtime(src) > os.path.getmtime(dst):
        shutil.copy2(src, dst)
        return True
    return False


class ApkHandler(SimpleHTTPRequestHandler):
    # Class attribute (not an override method): guess_type looks this up
    # as a dict, so the APK MIME type is merged into the stdlib map here.
    extensions_map = {
        **SimpleHTTPRequestHandler.extensions_map,
        ".apk": "application/vnd.android.package-archive",
    }

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=SERVE_DIR, **kwargs)

    def do_GET(self):
        refresh_apk()
        super().do_GET()

    def do_HEAD(self):
        refresh_apk()
        super().do_HEAD()

    def list_directory(self, path):
        """Minimal index page with sizes and build times."""
        refresh_apk()
        entries = []
        for name in sorted(os.listdir(SERVE_DIR)):
            full = os.path.join(SERVE_DIR, name)
            if os.path.isfile(full):
                mb = os.path.getsize(full) / (1024 * 1024)
                when = datetime.fromtimestamp(os.path.getmtime(full)).strftime("%Y-%m-%d %H:%M")
                entries.append(
                    f'<li><a href="/{urllib.parse.quote(name)}">{name}</a> '
                    f"&mdash; {mb:.1f} MB &mdash; built {when}</li>"
                )
        ip = local_ip()
        html = (
            "<html><head><meta charset='utf-8'>"
            "<title>YL-Harness APK</title>"
            "<meta name='viewport' content='width=device-width, initial-scale=1'></head>"
            "<body style='font-family:sans-serif;max-width:40em;margin:3em auto;padding:0 1em'>"
            "<h2>YL-Harness debug APK</h2>"
            f"<p>Direct link: <a href='/{APK_NAME}'>/{APK_NAME}</a></p>"
            f"<ul>{''.join(entries)}</ul>"
            f"<p style='color:#666'>Served from {ip}:{PORT} &mdash; auto-refreshes on each request</p>"
            "</body></html>"
        )
        # Like the stdlib implementation, send headers here and return a
        # file-like body for do_GET to copy and close.
        data = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        return io.BytesIO(data)


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


if __name__ == "__main__":
    refresh_apk()
    server = HTTPServer(("0.0.0.0", PORT), ApkHandler)
    print(f"Serving {SERVE_DIR}")
    print(f"Index:   http://{local_ip()}:{PORT}/")
    print(f"Direct:  http://{local_ip()}:{PORT}/{APK_NAME}")
    server.serve_forever()
