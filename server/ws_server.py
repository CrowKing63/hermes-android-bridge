#!/usr/bin/env python3
"""
Hermes Android Bridge — WebSocket Relay Server

This server runs on Nick (Termux/PRoot Ubuntu) and acts as a relay
between the Hermes Agent and the Android bridge app.

Protocol: JSON-RPC 2.0 over WebSocket
Connection: Android phone connects outbound to this server

Usage:
    python ws_server.py
    HERMES_BRIDGE_PORT=8765 python ws_server.py
"""

import asyncio
import json
import logging
import os
import signal
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Optional

import websockets
from websockets.server import WebSocketServerProtocol

# ─── Configuration ───

HOST = os.environ.get("HERMES_BRIDGE_HOST", "0.0.0.0")
PORT = int(os.environ.get("HERMES_BRIDGE_PORT", "8765"))
HEARTBEAT_INTERVAL = 60  # seconds
REQUEST_TIMEOUT = 30  # seconds

# ─── Logging ───

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("HermesBridgeServer")


# ─── Data Models ───


@dataclass
class ConnectedDevice:
    """Represents a connected Android device."""
    device_id: str
    websocket: WebSocketServerProtocol
    model: str = ""
    manufacturer: str = ""
    android_version: str = ""
    sdk_version: int = 0
    capabilities: list[str] = field(default_factory=list)
    connected_at: float = field(default_factory=time.time)
    last_ping: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return {
            "deviceId": self.device_id,
            "model": self.model,
            "manufacturer": self.manufacturer,
            "androidVersion": self.android_version,
            "sdkVersion": self.sdk_version,
            "capabilities": self.capabilities,
            "connectedAt": self.connected_at,
        }


# ─── Server State ───


class BridgeServer:
    """WebSocket bridge server for Hermes Android communication."""

    def __init__(self, host: str = HOST, port: int = PORT):
        self.host = host
        self.port = port
        self.devices: dict[str, ConnectedDevice] = {}  # device_id → device
        self._request_id = 0
        self._pending: dict[int, asyncio.Future] = {}  # request_id → Future
        self._lock = asyncio.Lock()

    @property
    def next_id(self) -> int:
        self._request_id += 1
        return self._request_id

    # ── Connection Management ──

    async def register(self, ws: WebSocketServerProtocol) -> Optional[ConnectedDevice]:
        """Register a new device connection. Returns the device or None."""
        headers = ws.request_headers if hasattr(ws, "request_headers") else {}
        device_id = headers.get("X-Device-ID", f"unknown_{uuid.uuid4().hex[:8]}")
        model = headers.get("X-Device-Model", "unknown")

        device = ConnectedDevice(
            device_id=device_id,
            websocket=ws,
            model=model,
        )

        async with self._lock:
            # Disconnect existing connection from same device
            if device_id in self.devices:
                old = self.devices[device_id]
                try:
                    await old.websocket.close(1000, "Reconnected from another session")
                except Exception:
                    pass
                logger.info(f"Replaced old connection for {device_id}")

            self.devices[device_id] = device

        logger.info(
            f"Device connected: {device_id} ({model}) — "
            f"total: {len(self.devices)}"
        )
        return device

    async def unregister(self, device_id: str):
        """Remove a device from active connections."""
        async with self._lock:
            removed = self.devices.pop(device_id, None)
        if removed:
            logger.info(f"Device disconnected: {device_id}")

    def get_device(self, device_id: str) -> Optional[ConnectedDevice]:
        return self.devices.get(device_id)

    def list_devices(self) -> list[dict]:
        return [d.to_dict() for d in self.devices.values()]

    # ── Command Sending ──

    async def send_command(
        self, device_id: str, method: str, params: dict | None = None, timeout: float = REQUEST_TIMEOUT
    ) -> dict:
        """
        Send a JSON-RPC request to a specific device and wait for response.

        Returns the result dict on success.
        Raises TimeoutError or RuntimeError on failure.
        """
        device = self.get_device(device_id)
        if not device:
            raise ValueError(f"Device '{device_id}' not connected")

        req_id = self.next_id
        request = {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": method,
            "params": params or {}
        }

        # Create future for response
        loop = asyncio.get_event_loop()
        future: asyncio.Future = loop.create_future()

        async with self._lock:
            self._pending[req_id] = future

        try:
            await device.websocket.send(json.dumps(request))
            logger.debug(f"[{device_id}] >> {method} (id={req_id})")

            result = await asyncio.wait_for(future, timeout=timeout)

            if "error" in result:
                raise RuntimeError(f"Device error: {result['error']}")

            return result.get("result", {})

        except asyncio.TimeoutError:
            raise TimeoutError(f"Device '{device_id}' did not respond to '{method}' in {timeout}s")
        finally:
            self._pending.pop(req_id, None)

    async def send_event(self, device_id: str, method: str, params: dict | None = None):
        """Send a one-way event to a device (no response expected)."""
        device = self.get_device(device_id)
        if not device:
            raise ValueError(f"Device '{device_id}' not connected")

        event = {
            "jsonrpc": "2.0",
            "method": method,
            "params": params or {}
        }
        await device.websocket.send(json.dumps(event))
        logger.debug(f"[{device_id}] event: {method}")

    async def broadcast_event(self, method: str, params: dict | None = None):
        """Send an event to all connected devices."""
        tasks = [
            self.send_event(device_id, method, params)
            for device_id in self.devices
        ]
        await asyncio.gather(*tasks, return_exceptions=True)

    # ── Incoming Message Handling ──

    async def handle_message(self, device: ConnectedDevice, raw: str):
        """Process an incoming message from a device."""
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            logger.warning(f"[{device.device_id}] Invalid JSON: {raw[:100]}")
            return

        msg_id = msg.get("id")
        method = msg.get("method", "")
        params = msg.get("params", {})
        result = msg.get("result")
        error = msg.get("error")

        logger.debug(f"[{device.device_id}] << method={method} id={msg_id}")

        # Response to our request
        if msg_id is not None and (result is not None or error is not None):
            future = self._pending.pop(msg_id, None)
            if future and not future.done():
                future.set_result(msg)
            return

        # Request from device (shouldn't happen often, but support it)
        if msg_id is not None and method:
            logger.debug(f"[{device.device_id}] Request: {method}")
            # Process device-originated requests
            response = await self._handle_device_request(device, method, params)
            await device.websocket.send(json.dumps({
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": response
            }))
            return

        # Event from device (no id)
        if method:
            await self._handle_device_event(device, method, params)

    async def _handle_device_request(self, device: ConnectedDevice, method: str, params: dict) -> dict:
        """Handle a request originating from the device."""
        logger.info(f"[{device.device_id}] Request: {method}({params})")
        return {"status": "ok", "message": f"Received {method}"}

    async def _handle_device_event(self, device: ConnectedDevice, method: str, params: dict):
        """Handle an event from the device (notification, status change, etc.)."""

        if method == "device.register":
            # Update device info from registration event
            device.device_id = params.get("deviceId", device.device_id)
            device.model = params.get("model", device.model)
            device.manufacturer = params.get("manufacturer", device.manufacturer)
            device.android_version = params.get("androidVersion", device.android_version)
            device.sdk_version = params.get("sdkVersion", device.sdk_version)
            device.capabilities = params.get("capabilities", [])
            logger.info(f"[{device.device_id}] Registered: {device.model} — capabilities: {device.capabilities}")

        elif method == "device.ping":
            device.last_ping = time.time()
            # Send pong
            await device.websocket.send(json.dumps({
                "jsonrpc": "2.0",
                "method": "device.pong",
                "params": {"timestamp": time.time()}
            }))

        elif method == "notification.posted":
            app = params.get("appName", params.get("packageName", "?"))
            title = params.get("title", "")
            text = params.get("text", "")
            logger.info(f"[{device.device_id}] Notification from {app}: {title} — {text}")

        elif method == "notification.removed":
            logger.debug(f"[{device.device_id}] Notification removed: {params.get('packageName')}")

        else:
            logger.debug(f"[{device.device_id}] Event: {method}({params})")

    # ── WebSocket Connection Handler ──

    async def connection_handler(self, ws: WebSocketServerProtocol):
        """Handle a single WebSocket connection."""
        device = None
        device_id = "unknown"

        try:
            device = await self.register(ws)
            if not device:
                return
            device_id = device.device_id

            async for raw in ws:
                try:
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8")
                    await self.handle_message(device, raw)
                except Exception as e:
                    logger.error(f"[{device_id}] Error processing message: {e}")

        except websockets.exceptions.ConnectionClosed as e:
            logger.info(f"[{device_id}] Connection closed: {e.code} — {e.reason}")
        except Exception as e:
            logger.error(f"[{device_id}] Unexpected error: {e}")
        finally:
            await self.unregister(device_id)


# ─── Main Server ───


async def main():
    """Start the WebSocket bridge server."""
    server = BridgeServer(host=HOST, port=PORT)

    logger.info(f"Starting Hermes Bridge Server on {HOST}:{PORT}")
    logger.info(f"Heartbeat interval: {HEARTBEAT_INTERVAL}s, Request timeout: {REQUEST_TIMEOUT}s")

    # Start WebSocket server
    stop = asyncio.Future()

    async with websockets.serve(
        server.connection_handler,
        HOST,
        PORT,
        ping_interval=30,
        ping_timeout=10,
        max_size=10 * 1024 * 1024,  # 10MB max message
    ):
        logger.info(f"Server ready. Waiting for device connections on ws://{HOST}:{PORT}/bridge")

        # Handle graceful shutdown
        loop = asyncio.get_event_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, stop.set_result(None))

        await stop

    logger.info("Server stopped.")


def cli_entry():
    """CLI entry point that prints info and starts the server."""
    print("=" * 60)
    print(" Hermes Android Bridge Server")
    print("=" * 60)
    print(f" Listening: ws://{HOST}:{PORT}/bridge")
    print(f" Press Ctrl+C to stop")
    print("=" * 60)
    print()

    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nServer stopped by user.")


if __name__ == "__main__":
    cli_entry()
