"""Event loop used by the local game backend."""
import asyncio
import sys


def loop_factory():
    # Uvicorn 0.49 selects Proactor on Windows. In the reported Python 3.14
    # session its accept loop repeatedly failed with WinError 64. This server
    # only uses TCP HTTP and threads, so it does not need proactor pipe support.
    if sys.platform == "win32":
        return asyncio.SelectorEventLoop()
    return asyncio.new_event_loop()
