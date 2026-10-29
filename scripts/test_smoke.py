import io
import unittest
from unittest.mock import patch

import smoke


class ReadinessTest(unittest.TestCase):
    def test_ci_startup_connection_reset_is_retried_until_ready(self):
        # The first hosted Compose run reset connections before Spring started.
        response = io.BytesIO(b'{"status":"UP"}')
        response.status = 200
        with patch.object(smoke.urllib.request, "urlopen", side_effect=[ConnectionResetError(), response]), \
                patch.object(smoke.time, "sleep"):
            smoke.wait_ready("http://localhost:8080/actuator/health/readiness")
        self.assertTrue(response.closed)

    def test_persistent_connection_failure_ends_with_readiness_error(self):
        with patch.object(smoke.urllib.request, "urlopen", side_effect=ConnectionResetError()), \
                patch.object(smoke.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "Service did not become ready"):
                smoke.wait_ready("http://localhost:8080/actuator/health/readiness")


if __name__ == "__main__":
    unittest.main()
