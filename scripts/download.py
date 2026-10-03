#!/usr/bin/env python3
"""Download benchmark files with size checks, resume and a free-disk guard.

Data lands under $FACETINDEX_DATA (default C:/SullaPortal/data/facetindex on the mini PC),
never inside the repo. Usage:
    python scripts/download.py yfcc            # filtered track, about 3 GB
    python scripts/download.py msturing10m     # streaming dev set, about 4 GB
    python scripts/download.py msturing30m     # streaming final set, about 12 GB
"""
import os
import shutil
import sys
import urllib.request

DATA = os.environ.get("FACETINDEX_DATA", "C:/SullaPortal/data/facetindex")
MIN_FREE_GB = float(os.environ.get("FACETINDEX_MIN_FREE_GB", "20"))

YFCC = "https://dl.fbaipublicfiles.com/billion-scale-ann-benchmarks/yfcc100M/"
KEY = 2727415019
SETS = {
    "yfcc": ("yfcc", [YFCC + f for f in [
        "base.10M.u8bin", "base.metadata.10M.spmat",
        "query.public.100K.u8bin", "query.metadata.public.100K.spmat", "GT.public.ibin",
        f"query.private.{KEY}.100K.u8bin", f"query.metadata.private.{KEY}.100K.spmat",
        f"GT.private.{KEY}.ibin", "unfiltered.GT.public.ibin"]]),
    "msturing10m": ("msturing10m", [
        "https://comp21storage.z5.web.core.windows.net/comp23/clustered_data/msturing-10M-clustered/" + f
        for f in ["msturing-10M-clustered.fbin", "testQuery10K.fbin", "clu_msturing10M_gt100"]]),
    "msturing30m": ("msturing30m", [
        "https://comp21storage.z5.web.core.windows.net/comp23/clustered_data/msturing-30M-clustered/" + f
        for f in ["30M-clustered64.fbin", "testQuery10K.fbin", "clu_msturing30M_gt100"]]),
}


def remote_size(url):
    req = urllib.request.Request(url, method="HEAD")
    with urllib.request.urlopen(req, timeout=60) as r:
        return int(r.headers["Content-Length"])


def fetch(url, out):
    size = remote_size(url)
    have = os.path.getsize(out) if os.path.exists(out) else 0
    if have == size:
        print(f"ok   {out} ({size} bytes)")
        return
    free_gb = shutil.disk_usage(os.path.dirname(out)).free / 1e9
    need_gb = (size - have) / 1e9
    if free_gb - need_gb < MIN_FREE_GB:
        sys.exit(f"disk guard: {free_gb:.1f} GB free, need {need_gb:.1f} GB plus {MIN_FREE_GB} GB margin")
    print(f"get  {url} -> {out} ({size} bytes, resuming at {have})", flush=True)
    req = urllib.request.Request(url, headers={"Range": f"bytes={have}-"} if have else {})
    with urllib.request.urlopen(req, timeout=120) as r, open(out, "ab" if have else "wb") as f:
        done = have
        while True:
            buf = r.read(1 << 22)
            if not buf:
                break
            f.write(buf)
            done += len(buf)
            if done % (1 << 28) < (1 << 22):
                print(f"     {done / 1e9:.2f} / {size / 1e9:.2f} GB", flush=True)
    got = os.path.getsize(out)
    if got != size:
        sys.exit(f"size check failed for {out}: {got} != {size}")
    print(f"ok   {out}")


def main():
    for name in sys.argv[1:] or ["yfcc"]:
        sub, urls = SETS[name]
        d = os.path.join(DATA, sub)
        os.makedirs(d, exist_ok=True)
        for u in urls:
            fetch(u, os.path.join(d, u.rsplit("/", 1)[1]))


if __name__ == "__main__":
    main()
