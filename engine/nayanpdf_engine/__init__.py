"""X PDF processing engine.

All document processing runs locally in this package. Nothing here opens a
network connection; the engine is driven over stdin/stdout by the Electron
main process (see server.py).
"""

__version__ = "1.1.0"
