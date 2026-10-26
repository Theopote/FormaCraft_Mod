import re
import html
xml = open(r"build/test-results/test/TEST-com.formacraft.server.compiler.Oct20RetryReplayTest.xml", encoding="utf-8").read()
for m in re.finditer(r'<testcase name="([^"]+)".*?<failure message="([^"]+)"', xml, re.S):
    print("===", m.group(1), "===")
    print(html.unescape(m.group(2))[:900])
    print()
