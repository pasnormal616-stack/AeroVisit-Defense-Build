"""Walk-in forecast tests for main.py (starter estimates until there is enough visit history). Run from this folder:
    PYTHONIOENCODING=utf-8 python -m unittest test_forecast -v

Uses the same fakes as test_chat_sessions (no Gemini key, MySQL, BERT model or internet needed).
"""
import unittest
from unittest import mock

import test_chat_sessions  # noqa: F401  (installs the fakes, then imports main)
import main

from fastapi.testclient import TestClient


class FakeCursor:
    def __init__(self, rows):
        self.rows = rows

    def execute(self, sql):
        pass

    def fetchall(self):
        return self.rows


class FakeDb:
    def __init__(self, rows):
        self.rows = rows

    def cursor(self):
        return FakeCursor(self.rows)

    def close(self):
        pass


class ForecastTest(unittest.TestCase):

    def setUp(self):
        self.client = TestClient(main.app)
        self.addCleanup(main.train_ai_engine)   # back to the starter estimates (no database in tests)

    def predict(self, weekday, weather):
        sunday = mock.patch.object(main.datetime, "datetime", wraps=main.datetime.datetime)
        fake_datetime = sunday.start()
        self.addCleanup(sunday.stop)
        fake_datetime.now.return_value = mock.Mock(isoweekday=lambda: weekday)
        with mock.patch.object(main, "get_live_manila_weather", return_value=(weather, "test weather")):
            return self.client.get("/predict?pre_booked=10").json()

    def retrain_with(self, rows):
        with mock.patch.object(main, "connect_db", return_value=FakeDb(rows)):
            main.train_ai_engine()

    def test_starter_estimates_follow_the_day_and_weather_tables(self):
        X, y = main.baseline_training_data()
        self.assertEqual(len(X), 28)                      # 7 days x 4 weather levels
        self.assertEqual(y[X.index([6, 1])], 45)          # clear Saturday
        self.assertEqual(y[X.index([6, 2])], 31)          # light rain Saturday (45 x 0.7, rounded)
        self.assertEqual(y[X.index([1, 4])], 0)           # typhoon

    def test_a_clear_sunday_without_history_predicts_about_forty_not_four_hundred(self):
        main.train_ai_engine()
        answer = self.predict(7, 1)
        self.assertTrue(30 <= answer["walk_ins"] <= 50, answer["walk_ins"])
        self.assertEqual(answer["total_prediction"], answer["walk_ins"] + 10)
        self.assertEqual(answer["data_source"], "baseline")
        self.assertLessEqual(answer["confidence_percentage"], main.BASELINE_MAX_CONFIDENCE)

    def test_heavy_rain_lowers_the_forecast(self):
        main.train_ai_engine()
        self.assertLess(self.predict(6, 3)["walk_ins"], self.predict(6, 1)["walk_ins"])

    def test_fewer_than_fourteen_days_of_history_keeps_the_starter_estimates(self):
        self.retrain_with([(d % 7 + 1, 300) for d in range(13)])
        self.assertTrue(main.using_baseline)
        self.assertEqual(self.predict(7, 1)["data_source"], "baseline")

    def test_fourteen_days_of_history_switches_to_live_learning(self):
        self.retrain_with([(d % 7 + 1, 25) for d in range(14)])
        self.assertFalse(main.using_baseline)
        answer = self.predict(7, 1)
        self.assertEqual(answer["data_source"], "live")
        self.assertEqual(answer["walk_ins"], 25)


if __name__ == "__main__":
    unittest.main()
