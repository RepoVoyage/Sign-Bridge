import io
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock

from server import RecognizeHandler


class RecognizeLoggingTest(unittest.TestCase):
    def test_cv_response_log_contains_clip_metadata_and_result_without_video(self):
        handler = object.__new__(RecognizeHandler)
        handler.path = '/v1/recognize'
        handler.request_meta = {
            'content_type': 'video/mp4', 'content_length': '123', 'video_sha256': 'abc123',
        }
        handler.wfile = io.BytesIO()
        handler.send_response = Mock()
        handler.send_header = Mock()
        handler.end_headers = Mock()
        result = {'status': 'OK', 'frames': 30, 'any_hand_fraction': 0.9,
                  'candidates': [{'label': '我', 'score': 0.8}], 'needsConfirmation': True}

        output = io.StringIO()
        with redirect_stdout(output):
            handler.reply(200, result)

        record = json.loads(output.getvalue())
        self.assertEqual(record['http_status'], 200)
        self.assertEqual(record['request']['video_sha256'], 'abc123')
        self.assertEqual(record['response'], result)
        self.assertEqual(json.loads(handler.wfile.getvalue()), result)
