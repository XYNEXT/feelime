#!/usr/bin/env python3
"""Static file server with HTTP Range support for scripts/dev-models.sh --serve.

The production ModelStore downloader resumes interrupted transfers with
Range requests; stdlib `python3 -m http.server` silently answers them with a
full 200 body, which corrupts the resumed .part file (appended body exceeds
the expected digest size). This handler implements single-range responses.

Usage: python3 serve-models.py <port> <directory>
"""

import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)$")


class RangeHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send_head(self):
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            return super().send_head()
        if not os.path.isfile(path):
            self.send_error(404, "File not found")
            return None
        size = os.path.getsize(path)
        ctype = self.guess_type(path)
        match = RANGE_RE.match(self.headers.get("Range", "") or "")
        start, end = 0, size - 1
        status = 200
        if match:
            if match.group(1):
                start = int(match.group(1))
                if match.group(2):
                    end = min(int(match.group(2)), size - 1)
            elif match.group(2):  # bytes=-N: final N bytes
                start = max(size - int(match.group(2)), 0)
            if start > end or start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.end_headers()
                return None
            status = 206
        try:
            f = open(path, "rb")
        except OSError:
            self.send_error(404, "File not readable")
            return None
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        f.seek(start)
        self._range_remaining = end - start + 1
        return _BoundedFile(f, self._range_remaining)


class _BoundedFile:
    """Read-only wrapper capping copyfile() at the negotiated range length."""

    def __init__(self, f, remaining):
        self._f = f
        self._remaining = remaining

    def read(self, n=-1):
        if self._remaining <= 0:
            return b""
        if n is None or n < 0 or n > self._remaining:
            n = self._remaining
        data = self._f.read(n)
        self._remaining -= len(data)
        return data

    def close(self):
        self._f.close()


def main():
    port, directory = int(sys.argv[1]), sys.argv[2]
    handler = lambda *a, **kw: RangeHandler(*a, directory=directory, **kw)
    server = ThreadingHTTPServer(("0.0.0.0", port), handler)
    print("serving %s (Range-aware) on 0.0.0.0:%d" % (directory, port), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
