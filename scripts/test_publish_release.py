"""Publication recovery tests; all network boundaries mocked, no remote writes."""
import json
import subprocess
import unittest
from unittest.mock import patch
import publish_release as publisher

class ReleaseLookupTest(unittest.TestCase):
    def lookup(self, status, body, pages=None):
        response=subprocess.CompletedProcess([], status, json.dumps(body).encode(),
            b"gh: Not Found (HTTP 404)" if status == 1 else b"gh: Forbidden (HTTP 403)")
        return patch.object(publisher.subprocess, "run", return_value=response), patch.object(
            publisher, "run", return_value=json.dumps(pages))

    def test_published_tag_read_does_not_list_or_create(self):
        release={"id":10,"tag_name":"v0.9","draft":False}
        query, listed=self.lookup(0,release)
        with query, listed as command:
            self.assertEqual(publisher.find_release("v0.9"),release)
            command.assert_not_called()

    def test_hidden_draft_recovers_original_id_across_pages(self):
        draft={"id":11,"tag_name":"v0.9","draft":True,"assets":[]}
        query, listed=self.lookup(1,{},[[{"id":10,"tag_name":"v0.8"}],[draft]])
        with query, listed:
            self.assertEqual(publisher.find_release("v0.9"),draft)

    def test_confirmed_missing_tag_returns_none(self):
        query, listed=self.lookup(1,{},[[{"id":10,"tag_name":"v0.8"}]])
        with query, listed:
            self.assertIsNone(publisher.find_release("v0.9"))

    def test_permission_failure_stops_without_missing_release_assumption(self):
        query, listed=self.lookup(2,{})
        with query, listed as command:
            with self.assertRaises(SystemExit):
                publisher.find_release("v0.9")
            command.assert_not_called()

    def test_duplicate_drafts_stop_instead_of_selecting_or_recreating(self):
        query, listed=self.lookup(1,{},[[{"id":11,"tag_name":"v0.9"},{"id":12,"tag_name":"v0.9"}]])
        with query, listed:
            with self.assertRaises(SystemExit):
                publisher.find_release("v0.9")

if __name__ == "__main__":
    unittest.main()
