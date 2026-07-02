$structure = "c:\Users\navib\Desktop\development\formacraft-1.21.10\src\main\java\com\formacraft\common\generation\structure"
$changed = 0
Get-ChildItem -Path $structure -Filter *.java -Recurse | ForEach-Object {
    if ($_.Name -eq "StructureSpecParsers.java") { return }
    $text = [IO.File]::ReadAllText($_.FullName)
    $orig = $text
    $className = $_.BaseName
    $needLog = $false
    $needParser = $false

    if ($text -match 'private static int getIntExtra\(BuildingSpec spec, String key, int def\)') {
        $text = [regex]::Replace($text,
            'private static int getIntExtra\(BuildingSpec spec, String key, int def\) \{.*?\r?\n    \}',
            "private static int getIntExtra(BuildingSpec spec, String key, int def) {`r`n        return StructureSpecParsers.extraInt(spec, key, def);`r`n    }",
            [System.Text.RegularExpressions.RegexOptions]::Singleline)
        $needParser = $true
    }
    if ($text -match 'private static int getInt\(Map<String, Object> extra, String key, int def\)') {
        $text = [regex]::Replace($text,
            'private static int getInt\(Map<String, Object> extra, String key, int def\) \{.*?\r?\n    \}',
            "private static int getInt(Map<String, Object> extra, String key, int def) {`r`n        return StructureSpecParsers.mapInt(extra, key, def);`r`n    }",
            [System.Text.RegularExpressions.RegexOptions]::Singleline)
        $needParser = $true
    }
    if ($text -match 'private static int getInt\(Object v, int def\)') {
        $text = [regex]::Replace($text,
            'private static int getInt\(Object v, int def\) \{.*?\r?\n    \}',
            "private static int getInt(Object v, int def) {`r`n        return StructureSpecParsers.intValue(v, def);`r`n    }",
            [System.Text.RegularExpressions.RegexOptions]::Singleline)
        $needParser = $true
    }

    if ($text -match 'catch \(Throwable ignored\) \{\s*\}') {
        $needLog = $true
        $text = [regex]::Replace($text, 'catch \(Throwable ignored\) \{\s*\}', 'catch (Throwable t) { LOG.debug("best-effort step failed", t); }')
    }
    if ($text -match 'catch \(Exception ignored\) \{\s*\}') {
        $needLog = $true
        $text = [regex]::Replace($text, 'catch \(Exception ignored\) \{\s*\}', 'catch (Exception e) { LOG.debug("best-effort step failed", e); }')
    }
    if ($text -match 'catch \(Exception ignored\) \{\s*\r?\n\s*\}') {
        $needLog = $true
        $text = [regex]::Replace($text, 'catch \(Exception ignored\) \{\s*\r?\n\s*\}', "catch (Exception e) {`r`n            LOG.debug(""best-effort step failed"", e);`r`n        }")
    }

    if ($needLog -and $text -notmatch 'import com\.formacraft\.common\.logging\.FcaLog') {
        $idx = $text.IndexOf("`nimport ")
        if ($idx -lt 0) { $idx = $text.IndexOf("`npublic ") }
        $text = $text.Insert($idx + 1, "import com.formacraft.common.logging.FcaLog;`r`n")
    }
    if ($needParser -and $text -notmatch 'StructureSpecParsers') {
        $idx = $text.IndexOf("`nimport ")
        if ($idx -lt 0) { $idx = $text.IndexOf("`npublic ") }
        $text = $text.Insert($idx + 1, "import com.formacraft.common.generation.structure.util.StructureSpecParsers;`r`n")
    }
    if ($needLog -and $text -notmatch "FcaLog\.of\(""$className""\)") {
        $text = [regex]::Replace($text,
            "(public (?:final )?(?:class|enum) $className[^{]*\{)",
            "`$1`r`n`r`n    private static final FcaLog LOG = FcaLog.of(""$className"");",
            1)
    }

    if ($text -ne $orig) {
        [IO.File]::WriteAllText($_.FullName, $text)
        $changed++
        Write-Host "updated: $($_.Name)"
    }
}
Write-Host "done: $changed files"
