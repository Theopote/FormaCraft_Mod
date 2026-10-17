import http.client
import socket
import subprocess
import sys
import time
import unittest
from pathlib import Path


class ServerRuntimeTest(unittest.TestCase):
    def test_custom_loop_serves_after_aborted_connections(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        process = subprocess.Popen(
            [sys.executable, '-m', 'uvicorn', 'app.main:app', '--host',
             '127.0.0.1', '--port', str(port), '--loop',
             'app.server_runtime:loop_factory'],
            cwd=Path(__file__).resolve().parents[1],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        try:
            deadline = time.monotonic() + 30
            while True:
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=1):
                        break
                except OSError:
                    self.assertIsNone(process.poll(), 'Backend exited during startup')
                    if time.monotonic() >= deadline:
                        self.fail('Backend did not start within 30 seconds')
                    time.sleep(0.1)
            for _ in range(5):
                with socket.create_connection(('127.0.0.1', port), timeout=2) as aborted:
                    aborted.sendall(b'GET /health HTTP/1.1\r\n')
                connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
                try:
                    connection.request('GET', '/health')
                    response = connection.getresponse()
                    self.assertEqual(200, response.status)
                    response.read()
                finally:
                    connection.close()
            self.assertIsNone(process.poll())
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
