"""Rate-limit and shared-secret tests for main.py. Run from this folder:
    PYTHONIOENCODING=utf-8 python -m unittest test_rate_limits -v

Uses the same fakes as test_chat_sessions (no Gemini key, MySQL, BERT model or internet needed). The limits are off
for the other test files; here each test switches them on and starts from empty windows.
"""
import unittest
from unittest import mock

import test_chat_sessions  # noqa: F401  (installs the fakes, then imports main)
import main

from fastapi.testclient import TestClient

BUCKETS = [main.CHAT_PER_SESSION, main.GEMINI_PER_MINUTE, main.GEMINI_PER_DAY, main.KYC_PER_MINUTE,
           main.ANALYZE_PER_MINUTE, main.PREDICT_PER_MINUTE, main.RETRAIN_WINDOW]


class FakeClock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


class SlidingWindowTest(unittest.TestCase):

    def setUp(self):
        self.on = mock.patch.object(main, "RATE_LIMITS_ENABLED", True)
        self.on.start()
        self.addCleanup(self.on.stop)
        self.clock = FakeClock()

    def test_allows_the_limit_then_says_how_long_to_wait(self):
        window = main.SlidingWindow(3, 60, clock=self.clock)
        self.assertEqual([window.hit("a") for _ in range(3)], [0, 0, 0])
        self.clock.now += 10
        self.assertEqual(window.hit("a"), 51)   # the oldest hit leaves the window 50 s from now (+1 rounding up)

    def test_the_window_slides(self):
        window = main.SlidingWindow(2, 60, clock=self.clock)
        window.hit("a")
        self.clock.now += 30
        window.hit("a")
        self.assertTrue(window.hit("a"))       # 2 in the last 60 s
        self.clock.now += 31                    # the first hit is now 61 s old
        self.assertEqual(window.hit("a"), 0)

    def test_keys_are_counted_separately(self):
        window = main.SlidingWindow(1, 60, clock=self.clock)
        self.assertEqual(window.hit("session-a"), 0)
        self.assertTrue(window.hit("session-a"))
        self.assertEqual(window.hit("session-b"), 0)

    def test_idle_keys_are_dropped_once_many_are_tracked(self):
        window = main.SlidingWindow(1, 60, max_keys=5, clock=self.clock)
        for i in range(6):
            window.hit(f"old-{i}")
        self.clock.now += 61
        window.hit("new")
        self.assertEqual(set(window.hits), {"new"})

    def test_off_switch_and_zero_limit_never_refuse(self):
        window = main.SlidingWindow(1, 60, clock=self.clock)
        with mock.patch.object(main, "RATE_LIMITS_ENABLED", False):
            self.assertEqual([window.hit() for _ in range(5)], [0] * 5)
        off = main.SlidingWindow(0, 60, clock=self.clock)
        self.assertEqual([off.hit() for _ in range(5)], [0] * 5)


class EndpointLimitTest(unittest.TestCase):

    def setUp(self):
        self.on = mock.patch.object(main, "RATE_LIMITS_ENABLED", True)
        self.on.start()
        self.addCleanup(self.on.stop)
        for bucket in BUCKETS:
            bucket.hits.clear()
        main.active_sessions.clear()
        self.client = TestClient(main.app)

    def chat(self, session_id, message="Where is the F-5A?"):
        return main.chat_with_bot(main.ChatRequest(message=message, session_id=session_id))

    def test_one_chat_session_gets_8_questions_a_minute(self):
        for _ in range(8):
            self.assertEqual(self.chat("SGT-ONE-111")["type"], "chat")
        refused = self.chat("SGT-ONE-111")
        self.assertEqual(refused.status_code, 429)
        self.assertIn(b'"reply":"' + main.CHAT_BUSY.encode(), refused.body)
        self.assertGreaterEqual(int(refused.headers["Retry-After"]), 1)
        self.assertEqual(self.chat("SGT-TWO-222")["type"], "chat")   # another visitor is not affected

    def test_all_chats_together_get_15_gemini_calls_a_minute(self):
        for i in range(15):
            self.assertEqual(self.chat(f"SGT-VISITOR-{i:03d}")["type"], "chat")
        self.assertEqual(self.chat("SGT-VISITOR-999").status_code, 429)

    def test_the_gemini_daily_budget_is_off_unless_configured(self):
        self.assertEqual(main.GEMINI_PER_DAY.limit, 0)

    def test_a_refused_chat_never_reaches_gemini(self):
        for _ in range(8):
            self.chat("SGT-QUOTA-333")
        chat = main.active_sessions["SGT-QUOTA-333"]
        sent_before = len(chat.history)
        self.assertEqual(self.chat("SGT-QUOTA-333").status_code, 429)
        self.assertEqual(len(chat.history), sent_before)

    def test_retrain_runs_at_most_once_per_5_minutes(self):
        with mock.patch.object(main, "train_ai_engine") as train:
            self.assertEqual(self.client.post("/retrain").status_code, 200)
            second = self.client.post("/retrain")
        self.assertEqual(second.status_code, 429)
        self.assertEqual(second.json()["type"], "error")
        self.assertIn("Retry-After", second.headers)
        self.assertEqual(train.call_count, 1)

    def test_kyc_endpoints_share_a_global_ceiling(self):
        for _ in range(30):
            main.KYC_PER_MINUTE.hit()
        for path in ("/kyc/verify", "/kyc/extract-id"):
            self.assertEqual(self.client.post(path, json={}).status_code, 429, path)

    def test_analyze_and_predict_have_global_ceilings(self):
        for _ in range(60):
            main.ANALYZE_PER_MINUTE.hit()
        for _ in range(30):
            main.PREDICT_PER_MINUTE.hit()
        self.assertEqual(self.client.get("/analyze", params={"text": "great"}).status_code, 429)
        self.assertEqual(self.client.get("/predict").status_code, 429)


class InternalKeyTest(unittest.TestCase):

    def setUp(self):
        self.client = TestClient(main.app)

    def test_with_a_key_configured_only_callers_with_it_get_in(self):
        body = {"message": "hi", "session_id": "bad id"}   # refused cheaply by /chat itself once past the key check
        with mock.patch.object(main, "AI_INTERNAL_KEY", "s3cret-key"):
            self.assertEqual(self.client.post("/chat", json=body).status_code, 401)
            self.assertEqual(self.client.post("/chat", json=body, headers={"X-Internal-Key": "wrong"}).status_code, 401)
            self.assertEqual(self.client.post("/retrain").status_code, 401)
            ok = self.client.post("/chat", json=body, headers={"X-Internal-Key": "s3cret-key"})
        self.assertEqual(ok.status_code, 200)

    def test_without_a_key_configured_nothing_is_checked(self):
        with mock.patch.object(main, "AI_INTERNAL_KEY", ""):
            self.assertEqual(self.client.post("/chat", json={"message": "hi", "session_id": "bad id"}).status_code, 200)


if __name__ == "__main__":
    unittest.main()
