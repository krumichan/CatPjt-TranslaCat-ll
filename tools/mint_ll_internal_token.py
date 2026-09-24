#!/usr/bin/env python3
"""로컬 smoke용 단기 토큰 생성. 운영 로그인/사용자 검증을 대신하는 서버 API가 아니다."""
from __future__ import annotations
import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time


def encode(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def mint(user_id: int, admin: bool, key: bytes, now: int, ttl: int, issuer: str, audience: str) -> str:
    if not 1 <= user_id <= 9223372036854775807:
        raise ValueError("user-id는 양수 Long 범위여야 합니다.")
    if not 32 <= len(key) <= 128 or not 1 <= ttl <= 120:
        raise ValueError("키 길이 또는 TTL이 유효하지 않습니다.")
    payload = {"iss": issuer, "aud": audience, "sub": str(user_id), "service": "translacat-be",
               "tokenUse": "ll-internal", "roles": ["ADMIN" if admin else "USER"], "iat": now, "exp": now + ttl}
    header = {"alg": "HS256", "typ": "JWT"}
    parts = [encode(json.dumps(value, separators=(",", ":")).encode()) for value in (header, payload)]
    signing = ".".join(parts).encode("ascii")
    return signing.decode("ascii") + "." + encode(hmac.new(key, signing, hashlib.sha256).digest())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--admin", action="store_true")
    parser.add_argument("--ttl", type=int, default=60)
    parser.add_argument("--issuer", default="translacat-be")
    parser.add_argument("--audience", default="translacat-ll")
    args = parser.parse_args()
    try:
        key = base64.b64decode(os.environ.get("LL_INTERNAL_JWT_SECRET_BASE64", ""), validate=True)
        print(mint(args.user_id, args.admin, key, int(time.time()), args.ttl, args.issuer, args.audience))
    except (ValueError, TypeError) as error:
        print("내부 토큰 생성 실패: 키 환경변수와 입력 범위를 확인해 주세요.", file=sys.stderr)
        raise SystemExit(2) from None


if __name__ == "__main__":
    main()
