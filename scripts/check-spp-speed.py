"""Bounded comparison of one and four connections to the configured SPP client host."""
import concurrent.futures
import hashlib
import json
import time
import urllib.request

URL = "http://ashamanecore.com/dicpics/LegionClient.zip"
SIZE = 16 * 1024 * 1024

def fetch(start, end, validator=None):
    headers = {"Range": f"bytes={start}-{end}", "Accept-Encoding": "identity"}
    if validator:
        headers["If-Range"] = validator
    with urllib.request.urlopen(urllib.request.Request(URL, headers=headers), timeout=60) as response:
        if response.status != 206 or not response.headers.get("Content-Range", "").startswith(f"bytes {start}-{end}/"):
            raise RuntimeError("The host did not honor the exact requested byte range")
        data = response.read(end - start + 2)
        if len(data) != end - start + 1:
            raise RuntimeError("Incomplete or oversized response")
        return data, response.headers.get("ETag")

baseline = None
etag = None
for connections in (1, 4, 1):
    start = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=connections) as pool:
        futures = [pool.submit(fetch, i * SIZE // connections, (i + 1) * SIZE // connections - 1, etag)
                   for i in range(connections)]
        parts = [future.result() for future in futures]
    if len({value[1] for value in parts}) != 1:
        raise RuntimeError("The source validator changed between connections")
    etag = parts[0][1]
    data = b"".join(value[0] for value in parts)
    digest = hashlib.sha256(data).hexdigest()
    if baseline is not None and baseline != digest:
        raise RuntimeError("Parallel bytes differ from sequential bytes")
    baseline = digest
    elapsed = time.monotonic() - start
    print("SPP_SPEED=" + json.dumps({"connections": connections, "bytes": len(data),
        "seconds": round(elapsed, 3), "MB_per_second": round(len(data) / elapsed / 1000000, 3),
        "etag": etag, "sha256": digest}), flush=True)
