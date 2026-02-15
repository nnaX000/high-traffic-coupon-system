#!/usr/bin/env python3
"""
users 테이블용 덤프 CSV 생성 (2000만 행).
목적: id, user_id, username, password, email 컬럼 데이터 생성 (PRIMARY 중복 0 방지).
출력: infra/seed/data/users.csv (탭 구분, LOAD DATA용).
"""
import os
import sys

TOTAL_ROWS = 20_000_000
BATCH_SIZE = 100_000
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "data")
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "users.csv")


def main():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    written = 0
    with open(OUTPUT_FILE, "w", encoding="utf-8", newline="") as f:
        for start in range(1, TOTAL_ROWS + 1, BATCH_SIZE):
            batch = []
            for i in range(start, min(start + BATCH_SIZE, TOTAL_ROWS + 1)):
                uid = f"user_{i}"
                batch.append(f"{i}\t{uid}\tUser{i}\tpassword{i}\tuser{i}@example.com")
            f.write("\n".join(batch) + "\n")
            written += len(batch)
            if written % 500_000 == 0 or written == TOTAL_ROWS:
                print(f"Written {written:,} / {TOTAL_ROWS:,} rows", file=sys.stderr)
    print(f"Done: {OUTPUT_FILE}", file=sys.stderr)


if __name__ == "__main__":
    main()
