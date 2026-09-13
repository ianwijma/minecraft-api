"""MAPI Python SDK (stdlib only).

A thin, dependency-free client for the MAPI local HTTP API with the
hand-written helper layer from spec section 10: token-file auth, discovery
parsing, task polling, event cursors with gap detection, and idempotent
task creation.
"""

from .client import MapiClient, Discovery, MapiError

__all__ = ["MapiClient", "Discovery", "MapiError"]
