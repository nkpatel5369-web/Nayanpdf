"""N PDF engine entry point (started by the desktop app).

Run with an isolated interpreter (python -I); this file puts its own folder on
sys.path explicitly so only the bundled engine package is importable.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from nayanpdf_engine.server import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
