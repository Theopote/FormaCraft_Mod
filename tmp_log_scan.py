# -*- coding: utf-8 -*-
import sys, re
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
path = r"F:\development\formacraft-1.21.10\run\logs\latest.log"
with open(path, "r", encoding="utf-8", errors="replace") as f:
    lines = f.readlines()

print("=== ALL event=error ===")
for i, l in enumerate(lines):
    if "event=error" in l or "Capability gap" in l:
        print(f"{i+1}: {l.strip()[:320]}")

print("\n=== Build requests (user prompts) ===")
for i, l in enumerate(lines):
    if "Received build request" in l or ("userMessage" in l and "生成" in l):
        print(f"{i+1}: {l.strip()[:260]}")

print("\n=== Water pavilion / greenhouse / palace / hall / colonnade ===")
keys = ["水上", "亭", "温室", "宫殿", "柱廊", "大厅", "宽台阶", "白墙", "金顶"]
for i, l in enumerate(lines):
    if any(k in l for k in keys) and ("Received" in l or "prompt=" in l or "userMessage" in l or "success" in l or "error" in l):
        print(f"{i+1}: {l.strip()[:300]}")
