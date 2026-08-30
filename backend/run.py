"""Convenience launcher:  python run.py

Listens on 0.0.0.0 so phones/tablets on the same LAN can reach it too
(e.g. http://<this-pc-LAN-ip>:8000). To restrict to this machine only,
use:  python run.py 127.0.0.1
"""
import sys

import uvicorn

if __name__ == "__main__":
    host = sys.argv[1] if len(sys.argv) > 1 else "0.0.0.0"
    uvicorn.run("app.main:app", host=host, port=8000, reload=False)
