"""
Forecast accuracy test (MAE and RMSE) for AeroVisit's two forecasting models.

It rebuilds each model exactly the way the system does, hides the newest days, asks the model to predict them, and
compares the predictions with what really happened. Nothing in the system or database is changed (read-only).

  Linear Regression  the Admin dashboard's 7-day forecast chart (AdminDashboardController, Apache SimpleRegression):
                     a straight line fitted to the last 30 days, extended 7 days ahead. Tested as a rolling backtest:
                     every day in the test period is treated as "today", and its next 7 days are forecast.
  Random Forest      the AI service's walk-in prediction (AeroVisit-AI/main.py): 100 trees on [day of week, weather],
                     trained on daily check-in counts. Tested with a date-ordered 80/20 split (train on the older days,
                     predict the newer ones). The weather values are random, as in main.py, because the system does
                     not yet store the real weather of past days.

Each model is also compared with a simple "naive" guess, so the scores can be judged:
  Linear Regression vs. "next days = average of the last 30 days"
  Random Forest     vs. "each day = the training average for that weekday"

Usage (from the AeroVisit-AI folder, against the LOCAL database only):
  python evaluation/evaluate_forecast.py                    # real visits (default)
  python evaluation/evaluate_forecast.py --data simulated   # demo data from /admin/inject-mock-data
  python evaluation/evaluate_forecast.py --csv results.csv  # also save every prediction next to its actual value

The database settings come from the flags below, not from .env, so this never reaches the live (RDS) database by
accident.
"""
import argparse
import csv
import datetime
import math
import random
import sys

import mysql.connector
import numpy as np
from sklearn.ensemble import RandomForestRegressor

# Same rules as the system
LR_WINDOW_DAYS = 30          # AdminDashboardController: last 30 days, today included
LR_HORIZON_DAYS = 7          # ... forecast 7 days ahead
LR_STATUSES = ("APPROVED", "CHECKED-IN", "CHECKED-OUT", "NO_SHOW")   # counted with their group size
RF_STATUSES = ("CHECKED-IN", "CHECKED-OUT")                         # main.py: one row = one count
RF_MIN_HISTORY_DAYS = 14     # main.py: fewer days than this = starter estimates, nothing to test
TEST_SHARE = 0.20

# How simulated rows are recognised (same patterns as VisitorRepository / MockVisitorGenerator)
SIMULATED_SQL = ("(email LIKE '%@mock.aerovisit' OR purpose = 'Historical Baseline'"
                 " OR (name LIKE 'Civilian Trainee - %' AND email LIKE 'civ\\_%@mail.com'))")


def parse_args():
    p = argparse.ArgumentParser(description="MAE / RMSE test for AeroVisit's forecasts (read-only).")
    p.add_argument("--data", choices=["real", "simulated", "all"], default="real",
                   help="which visits to test on (default: real)")
    p.add_argument("--host", default="localhost")
    p.add_argument("--port", type=int, default=3306)
    p.add_argument("--user", default="root")
    p.add_argument("--password", default="root")
    p.add_argument("--database", default="aerovisit_db_v2")
    p.add_argument("--csv", help="save every prediction and its actual value to this CSV file")
    return p.parse_args()


def load_rows(args):
    if args.host not in ("localhost", "127.0.0.1", "::1"):
        sys.exit("Refusing to run against a non-local database. Copy the data to your local MySQL first.")
    where = {"real": "NOT " + SIMULATED_SQL, "simulated": SIMULATED_SQL, "all": "1=1"}[args.data]
    db = mysql.connector.connect(host=args.host, port=args.port, user=args.user,
                                 password=args.password, database=args.database)
    try:
        cur = db.cursor()
        cur.execute(f"SELECT visit_date, status, group_size FROM visitor WHERE visit_date IS NOT NULL AND {where}")
        return cur.fetchall()
    finally:
        db.close()


def scores(actual, predicted):
    errors = np.array(predicted, dtype=float) - np.array(actual, dtype=float)
    return float(np.mean(np.abs(errors))), float(math.sqrt(np.mean(errors ** 2)))


# ---------------------------------------------------------------- Linear Regression (7-day chart)

def lr_daily_series(rows):
    """Visitors per calendar day (sum of group sizes, at least 1 per booking); days with no visits count as 0."""
    totals = {}
    for visit_date, status, group_size in rows:
        if status in LR_STATUSES:
            totals[visit_date] = totals.get(visit_date, 0) + max(1, group_size or 0)
    if not totals:
        return [], []
    start, end = min(totals), max(totals)
    days = [start + datetime.timedelta(d) for d in range((end - start).days + 1)]
    return days, [totals.get(d, 0) for d in days]


def fit_line(ys):
    """Least squares, x = 1..n (same as SimpleRegression.addData(dayIndex, count))."""
    xs = np.arange(1, len(ys) + 1, dtype=float)
    y = np.array(ys, dtype=float)
    x_mean, y_mean = xs.mean(), y.mean()
    denom = ((xs - x_mean) ** 2).sum()
    slope = ((xs - x_mean) * (y - y_mean)).sum() / denom if denom else 0.0
    return y_mean - slope * x_mean, slope


def test_linear_regression(rows):
    days, counts = lr_daily_series(rows)
    n = len(days)
    first_test = max(LR_WINDOW_DAYS, int(round(n * (1 - TEST_SHARE))))
    if n < LR_WINDOW_DAYS + LR_HORIZON_DAYS or first_test >= n:
        return None, f"needs at least {LR_WINDOW_DAYS + LR_HORIZON_DAYS} days in a row, found {n}"

    out = []   # (horizon, target day, actual, model forecast, naive forecast)
    # Every day before a test-period day can be "today"; its forecasts that land in the test period are scored
    for today in range(LR_WINDOW_DAYS - 1, n - 1):
        window = counts[today - LR_WINDOW_DAYS + 1: today + 1]
        a, b = fit_line(window)
        naive = round(sum(window) / len(window))
        for h in range(1, LR_HORIZON_DAYS + 1):
            target = today + h
            if target < first_test or target >= n:
                continue
            forecast = max(0, round(a + b * (LR_WINDOW_DAYS + h)))   # x = 31..37, never below 0
            out.append((h, days[target], counts[target], forecast, naive))
    return {"rows": out, "days": n, "test_from": days[first_test], "test_to": days[-1]}, None


# ---------------------------------------------------------------- Random Forest (walk-in prediction)

def rf_daily_series(rows):
    """Check-in rows per visit date that has any (main.py GROUP BY visit_date), oldest first."""
    counts = {}
    for visit_date, status, _ in rows:
        if status in RF_STATUSES:
            counts[visit_date] = counts.get(visit_date, 0) + 1
    days = sorted(counts)
    return days, [counts[d] for d in days]


def test_random_forest(rows):
    days, counts = rf_daily_series(rows)
    n = len(days)
    split = int(round(n * (1 - TEST_SHARE)))
    if split < RF_MIN_HISTORY_DAYS or split >= n:
        return None, (f"needs at least {RF_MIN_HISTORY_DAYS} training days plus test days, found {n} days with "
                      "check-ins (below 14, the system uses its starter estimates, which can't be tested fairly)")

    rng = random.Random(42)   # main.py draws weather 1/2/3 with weights 0.7/0.2/0.1; fixed seed = repeatable result
    weather = [rng.choices([1, 2, 3], weights=[0.7, 0.2, 0.1])[0] for _ in days]
    features = np.array([[d.isoweekday(), w] for d, w in zip(days, weather)])   # WEEKDAY()+1 = Monday 1 .. Sunday 7

    model = RandomForestRegressor(n_estimators=100, random_state=42)
    model.fit(features[:split], np.array(counts[:split]))
    predicted = [int(p) for p in model.predict(features[split:])]   # main.py truncates with int()

    by_weekday = {}
    for d, c in zip(days[:split], counts[:split]):
        by_weekday.setdefault(d.isoweekday(), []).append(c)
    overall = sum(counts[:split]) / split
    naive = [round(np.mean(by_weekday.get(d.isoweekday(), [overall]))) for d in days[split:]]

    out = [(1, d, a, p, nv) for d, a, p, nv in zip(days[split:], counts[split:], predicted, naive)]
    return {"rows": out, "days": n, "train_days": split, "test_from": days[split], "test_to": days[-1]}, None


# ---------------------------------------------------------------- report

def print_result(title, result, error, extra=""):
    print(f"\n{title}")
    if error:
        print(f"  Not enough data: {error}.")
        return
    rows = result["rows"]
    mae, rmse = scores([r[2] for r in rows], [r[3] for r in rows])
    n_mae, n_rmse = scores([r[2] for r in rows], [r[4] for r in rows])
    mean_actual = np.mean([r[2] for r in rows])
    print(f"  Test period : {result['test_from']} to {result['test_to']}  ({len(rows)} predictions{extra})")
    print(f"  Average actual visitors per day in the test period: {mean_actual:.1f}")
    print(f"  Model       : MAE = {mae:.2f} visitors   RMSE = {rmse:.2f} visitors")
    print(f"  Naive guess : MAE = {n_mae:.2f} visitors   RMSE = {n_rmse:.2f} visitors")
    if title.startswith("Linear"):
        next_day = [r for r in rows if r[0] == 1]
        if next_day:
            m1, r1 = scores([r[2] for r in next_day], [r[3] for r in next_day])
            print(f"  Next day only (1 day ahead): MAE = {m1:.2f}   RMSE = {r1:.2f}")


def main():
    args = parse_args()
    rows = load_rows(args)
    label = {"real": "REAL visits", "simulated": "SIMULATED (demo) visits", "all": "ALL visits (real + simulated)"}[args.data]
    print("=" * 72)
    print(f"AeroVisit forecast accuracy test  |  data: {label}  |  {len(rows)} booking rows")
    print("=" * 72)

    lr, lr_err = test_linear_regression(rows)
    rf, rf_err = test_random_forest(rows)
    print_result("Linear Regression (7-day forecast chart)", lr, lr_err,
                 extra=f", 1 to {LR_HORIZON_DAYS} days ahead, {LR_WINDOW_DAYS}-day window" if lr else "")
    print_result("Random Forest (walk-in prediction)", rf, rf_err,
                 extra=f", trained on {rf['train_days']} older days" if rf else "")
    print("\nNote: the Random Forest's weather values are random (as in main.py), not the real weather of each day.")
    if args.data != "real":
        print("Note: these scores come from simulated data. Say so wherever they are reported.")

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["model", "days_ahead", "date", "actual", "predicted", "naive_guess"])
            for name, result in (("linear_regression", lr), ("random_forest", rf)):
                for h, d, a, p, nv in (result["rows"] if result else []):
                    w.writerow([name, h, d, a, p, nv])
        print(f"Saved every prediction to {args.csv}")


if __name__ == "__main__":
    main()
