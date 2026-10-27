from pathlib import Path
import re

cpc = Path("src/main/java/com/formacraft/server/compiler/ComponentPlanCompiler.java")
inf = Path("src/main/java/com/formacraft/server/compiler/ComponentSatelliteInference.java")
lines = cpc.read_text(encoding="utf-8").splitlines()
print("CPC", len(lines))

keys = (
    "generateAssemblyFacade",
    "buildAssemblyMacro",
    "resolveAssemblyStyle",
    "resolveAssemblyPalette",
    "markAssemblyFacade",
    "shouldUseAssemblyFacade",
    "needsAssemblyFacade",
    "filterAssemblyFacade",
    "buildDoorOpening",
    "directionToFaceToken",
    "resolveEntranceFacing",
    "resolveMassOrigin",
    "clamp01",
    "getParamBoolean",
    "MetaAssembly",
)

methods = []
for i, l in enumerate(lines):
    if re.match(r"\s+(private |static |public ).*", l) and "(" in l:
        methods.append((i + 1, l.strip()))

for idx, (line, sig) in enumerate(methods):
    end = methods[idx + 1][0] if idx + 1 < len(methods) else len(lines) + 1
    if any(k.lower() in sig.lower() for k in keys) or "Assembly" in sig:
        print(f"{end-line:4d} L{line}-{end-1}: {sig[:110]}")

print("\n=== calls in CPC ===")
text = "\n".join(lines)
for name in [
    "generateAssemblyFacadePatches",
    "markAssemblyFacade",
    "shouldUseAssemblyFacade",
    "needsAssemblyFacadeProfile",
    "buildAssemblyMacroStyle",
    "resolveAssemblyStyleId",
    "resolveMassOrigin",
]:
    print(name, text.count(name))

print("\n=== Inference assembly ===")
it = inf.read_text(encoding="utf-8")
for name in ["markAssemblyFacade", "shouldUseAssemblyFacade", "needsAssemblyFacadeProfile"]:
    print(name, name in it)

print("\n=== tests ===")
pat = re.compile(r"AssemblyFacade|assembly_facade|generateAssemblyFacade|AssemblyComposition")
for f in Path("src/test").rglob("*.java"):
    t = f.read_text(encoding="utf-8")
    if pat.search(t) and "compiler" in str(f).replace("\\", "/"):
        print(f.name)
