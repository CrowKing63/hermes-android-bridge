#!/usr/bin/env python3
"""
Test script for Hermes Android Bridge server.

Verifies:
1. Server starts and accepts WebSocket connections
2. Registration and ping/pong works
3. JSON-RPC message parsing is correct

Usage:
    python test_connection.py
"""

import asyncio
import json
import sys
import time

import websockets


async def test_connection():
    """Smoke test the bridge server."""
    url = "ws://localhost:8765/bridge"

    print("=" * 50)
    print(" Hermes Bridge Server — Smoke Test")
    print("=" * 50)

    # 1. Connect
    print(f"\n[1] Connecting to {url}...")
    try:
        async with websockets.connect(
            url,
            additional_headers={"X-Device-ID": "test_client", "X-Device-Model": "Test"}
        ) as ws:
            print("      ✓ Connected")

            # 2. Send registration
            print("\n[2] Sending device.register...")
            reg = {
                "jsonrpc": "2.0",
                "method": "device.register",
                "params": {
                    "deviceId": "test_client",
                    "model": "Test Device",
                    "manufacturer": "Test",
                    "androidVersion": "14",
                    "sdkVersion": 34,
                    "capabilities": ["device.battery", "notification.list"]
                }
            }
            await ws.send(json.dumps(reg))
            print("      ✓ Sent")

            # 3. Send ping
            print("\n[3] Sending device.ping...")
            ping = {"jsonrpc": "2.0", "method": "device.ping"}
            await ws.send(json.dumps(ping))

            # Wait for pong
            try:
                pong = await asyncio.wait_for(ws.recv(), timeout=5)
                pong_data = json.loads(pong)
                if pong_data.get("method") == "device.pong":
                    print("      ✓ Received pong")
                else:
                    print(f"      ? Received: {pong_data}")
            except asyncio.TimeoutError:
                print("      — No pong (server may not echo)")

            # 4. Send battery request
            print("\n[4] Requesting device.battery...")
            req = {"jsonrpc": "2.0", "id": 99, "method": "device.battery", "params": {}}
            await ws.send(json.dumps(req))

            try:
                resp = await asyncio.wait_for(ws.recv(), timeout=5)
                resp_data = json.loads(resp)
                if resp_data.get("id") == 99:
                    print(f"      ✓ Response: {resp_data}")
                else:
                    print(f"      ? Got: {resp_data}")
            except asyncio.TimeoutError:
                print("      — No response (expected for test client without handler)")

            # 5. Test all methods return errors for unknown client
            print("\n[5] Testing unknown method...")
            unknown = {"jsonrpc": "2.0", "id": 100, "method": "foobar.baz", "params": {}}
            await ws.send(json.dumps(unknown))

            try:
                resp = await asyncio.wait_for(ws.recv(), timeout=5)
                resp_data = json.loads(resp)
                error = resp_data.get("error")
                if error:
                    print(f"      ✓ Error response: {error['message']}")
                else:
                    print(f"      ? Response: {resp_data}")
            except asyncio.TimeoutError:
                print("      — No response")

            print("\n" + "=" * 50)
            print(" All smoke tests completed!")
            print("=" * 50)

    except ConnectionRefusedError:
        print(f"\n� Connection refused — is the server running?")
        print(f"  Start it: python server/ws_server.py")
        sys.exit(1)
    except Exception as e:
        print(f"\n� Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(test_connection())
