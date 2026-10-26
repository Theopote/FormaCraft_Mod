import re
xml = open(r"build/test-results/test/TEST-com.formacraft.server.compiler.ComplexBuildRetest.xml", encoding="utf-8").read()
for m in re.finditer(r'<testcase name="logged plan ([^"]+)".*?<failure message="([^"]+)"', xml, re.S):
    msg = m.group(2).replace("&quot;", '"').replace("&#xA;", "\n").replace("&amp;", "&")
    print("===", m.group(1), "===")
    print(msg[:900])
    print()
