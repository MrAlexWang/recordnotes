"""带断点续传的后台下载器。
用法: python tools/dl.py <url> <目标文件> [日志文件]
"""
import os
import sys
import time
import urllib.request

CHUNK = 1 << 20


def download(url: str, dst: str) -> None:
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    for attempt in range(1, 9):
        have = os.path.getsize(dst) if os.path.exists(dst) else 0
        headers = {"User-Agent": "Mozilla/5.0"}
        if have > 0:
            headers["Range"] = f"bytes={have}-"
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=120) as r:
                total = r.headers.get("content-length")
                total = int(total) + have if total else None
                mode = "ab" if have and r.status == 206 else "wb"
                if mode == "wb":
                    have = 0
                with open(dst, mode) as f:
                    while True:
                        chunk = r.read(CHUNK)
                        if not chunk:
                            break
                        f.write(chunk)
                        have += len(chunk)
                        if total:
                            pct = have * 100.0 / total
                            print(f"{os.path.basename(dst)} {have / 1048576:.1f}MB / "
                                  f"{total / 1048576:.1f}MB ({pct:.1f}%)", flush=True)
            size = os.path.getsize(dst)
            if total is None or size >= total:
                print(f"DONE {dst} {size / 1048576:.1f}MB", flush=True)
                return
            print(f"INCOMPLETE {dst} {size}/{total}, retry", flush=True)
        except Exception as e:  # noqa: BLE001
            print(f"RETRY {attempt} {type(e).__name__}: {e}", flush=True)
            time.sleep(3 * attempt)
    print(f"FAILED {dst}", flush=True)


if __name__ == "__main__":
    download(sys.argv[1], sys.argv[2])
