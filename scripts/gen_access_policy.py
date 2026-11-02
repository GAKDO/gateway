#!/usr/bin/env python3
"""contracts OpenAPI의 x-trace-access를 gateway의 access-policy.yaml로 옮긴다 (GW1).

사용법:
  python3 scripts/gen_access_policy.py ../contracts          # 파일을 다시 쓴다
  python3 scripts/gen_access_policy.py ../contracts --check  # 다르면 1로 끝난다

규칙:
  - /api/ 로 시작하지 않는 경로(/.well-known/jwks.json)는 넣지 않는다. 밖으로 열지 않으므로 gateway에서 404다.
  - security가 비었거나 bearerJwt가 없는 operation(리프레시 쿠키)은 public: true.
  - 나머지는 x-trace-access의 roles·scope·captureToken을 그대로 옮긴다. 손으로 고치지 않는다.
"""
from __future__ import annotations

import pathlib
import subprocess
import sys

import yaml

METHODS = ("get", "post", "put", "patch", "delete")
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/access-policy.yaml"


def contracts_commit(root: pathlib.Path) -> str:
    try:
        return subprocess.run(["git", "-C", str(root), "rev-parse", "--short", "HEAD"],
                              check=True, capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def build_rules(root: pathlib.Path) -> list[dict]:
    rules: list[dict] = []
    for spec in sorted((root / "openapi").glob("*-server.yaml")):
        doc = yaml.safe_load(spec.read_text(encoding="utf-8"))
        default_security = doc.get("security")
        for path, item in doc["paths"].items():
            if not path.startswith("/api/"):
                continue
            for method in METHODS:
                op = item.get(method)
                if op is None:
                    continue
                security = op.get("security", default_security) or []
                bearer = any("bearerJwt" in s for s in security)
                rule: dict = {"method": method.upper(), "path": path}
                if not bearer:
                    rule["public"] = True
                else:
                    access = op.get("x-trace-access")
                    if not access:
                        raise SystemExit(f"{spec.name} {method.upper()} {path}: x-trace-access가 없습니다")
                    rule["roles"] = list(access["roles"])
                    rule["scope"] = access["scope"]
                    rule["captureToken"] = bool(access.get("captureToken", False))
                rules.append(rule)
    return rules


def render(rules: list[dict], commit: str) -> str:
    header = (
        "# 생성 파일: scripts/gen_access_policy.py가 contracts OpenAPI의 x-trace-access로 만든다. 손으로 고치지 않는다.\n"
        f"# contracts 커밋: {commit}\n"
    )
    body = yaml.safe_dump({"rules": rules}, allow_unicode=True, sort_keys=False, default_flow_style=None, width=120)
    return header + body


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    root = pathlib.Path(sys.argv[1])
    rules = build_rules(root)
    text = render(rules, contracts_commit(root))
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        # 커밋 줄은 비교에서 뺀다(같은 내용이면 통과)
        strip = lambda s: "\n".join(line for line in s.splitlines() if not line.startswith("# contracts 커밋"))
        if strip(current) != strip(text):
            print("access-policy.yaml이 contracts와 다릅니다. 스크립트를 다시 돌리세요.", file=sys.stderr)
            return 1
        print(f"access-policy.yaml 일치 (규칙 {len(rules)}개)")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"{OUT.name}: 규칙 {len(rules)}개 (contracts {contracts_commit(root)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
