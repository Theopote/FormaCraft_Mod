# Summarize [LlmPlanMetrics] fallback detail distribution from server logs.
#
# Usage:
#   .\scripts\analyze-llmplan-metrics.ps1
#   .\scripts\analyze-llmplan-metrics.ps1 run\logs\latest.log
#   Get-Content run\logs\latest.log | .\scripts\analyze-llmplan-metrics.ps1 -

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$LogPaths = @("run/logs/latest.log")
)

$ErrorActionPreference = "Stop"
$Marker = "[LlmPlanMetrics]"
$Actionable = @("MISSING_LLM_PLAN_JSON", "EMPTY_OUTPUT")

function Get-MetricLines {
    param([string[]]$Paths)
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($path in $Paths) {
        if ($path -eq "-") {
            $input | ForEach-Object { $lines.Add($_) }
            continue
        }
        $resolved = Resolve-Path -LiteralPath $path -ErrorAction Stop
        Get-Content -LiteralPath $resolved -Encoding UTF8 | ForEach-Object { $lines.Add($_) }
    }
    return $lines | Where-Object { $_ -like "*$Marker*" }
}

function Write-Bar {
    param(
        [string]$Label,
        [int]$Count,
        [int]$Total,
        [int]$Width = 28
    )
    if ($Total -le 0) { $fill = 0 } else { $fill = [Math]::Max(0, [Math]::Min($Width, [int][Math]::Round($Width * $Count / $Total))) }
    $bar = ("#" * $fill) + ("-" * ($Width - $fill))
    $pct = if ($Total -le 0) { "0.0%" } else { "{0:N1}%" -f (100.0 * $Count / $Total) }
    Write-Output ("  {0,-28} {1,5}  {2,6}  |{3}|" -f $Label, $Count, $pct, $bar)
}

$lines = Get-MetricLines -Paths $LogPaths
if (-not $lines -or $lines.Count -eq 0) {
    Write-Error "No $Marker lines found."
    exit 1
}

$eventCounts = @{}
$fallbackByDetail = @{}
$lastSnapshot = $null

foreach ($line in $lines) {
    if ($line -notmatch "event=(\w+)") { continue }
    $event = $Matches[1]
    if (-not $eventCounts.ContainsKey($event)) { $eventCounts[$event] = 0 }
    $eventCounts[$event]++

    if ($event -eq "fallback") {
        $detail = if ($line -match "detail=(\w+)") { $Matches[1] } else { "(missing)" }
        if (-not $fallbackByDetail.ContainsKey($detail)) { $fallbackByDetail[$detail] = 0 }
        $fallbackByDetail[$detail]++
    }

    if ($line -match "tagged=(\d+) success=(\d+) errors=(\d+) fallback=(\d+) fallback_rate=([\d.]+)% success_rate=([\d.]+)% direct_structure=(\d+) structure_after_fallback=(\d+)") {
        $lastSnapshot = [ordered]@{
            tagged                    = [int]$Matches[1]
            success                   = [int]$Matches[2]
            errors                    = [int]$Matches[3]
            fallback                  = [int]$Matches[4]
            fallback_rate_percent     = [double]$Matches[5]
            success_rate_percent      = [double]$Matches[6]
            direct_structure          = [int]$Matches[7]
            structure_after_fallback  = [int]$Matches[8]
        }
    }
}

$totalFallback = ($fallbackByDetail.Values | Measure-Object -Sum).Sum
if (-not $totalFallback) { $totalFallback = 0 }

$actionable = 0
foreach ($key in $Actionable) {
    if ($fallbackByDetail.ContainsKey($key)) { $actionable += $fallbackByDetail[$key] }
}

Write-Output "=== LlmPlan metrics summary ==="
Write-Output "Lines parsed: $($lines.Count)"
Write-Output ""
Write-Output "Event counts (log lines, not deduplicated requests):"
$eventCounts.GetEnumerator() | Sort-Object -Property Value -Descending | ForEach-Object {
    Write-Output ("  {0,-28} {1,5}" -f $_.Key, $_.Value)
}
Write-Output ""
Write-Output "Fallback by detail:"
if ($totalFallback -eq 0) {
    Write-Output "  (no fallback events)"
} else {
    $fallbackByDetail.GetEnumerator() | Sort-Object -Property Value -Descending | ForEach-Object {
        Write-Bar -Label $_.Key -Count $_.Value -Total $totalFallback
    }
}
Write-Output ""

if ($lastSnapshot) {
    $tagged = $lastSnapshot.tagged
    Write-Output "Latest cumulative snapshot (from last matching line):"
    Write-Output ("  tagged={0}  success={1}  errors={2}  fallback={3}" -f $tagged, $lastSnapshot.success, $lastSnapshot.errors, $lastSnapshot.fallback)
    Write-Output ("  fallback_rate={0:N1}%  success_rate={1:N1}%" -f $lastSnapshot.fallback_rate_percent, $lastSnapshot.success_rate_percent)
    Write-Output ("  direct_structure={0}  structure_after_fallback={1}" -f $lastSnapshot.direct_structure, $lastSnapshot.structure_after_fallback)
    Write-Output ""

    if ($tagged -gt 0) {
        $actionableRate = 100.0 * $actionable / $tagged
        $policyCount = if ($fallbackByDetail.ContainsKey("ROUTING_POLICY")) { $fallbackByDetail["ROUTING_POLICY"] } else { 0 }
        $policyShare = if ($totalFallback -gt 0) { "{0:N1}%" -f (100.0 * $policyCount / $totalFallback) } else { "n/a" }
        Write-Output "Derived (see docs/MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md section 9):"
        Write-Output ("  actionable_fallback_lines={0}  ({1:N1}% of fallback lines)" -f $actionable, $(if ($totalFallback -gt 0) { 100.0 * $actionable / $totalFallback } else { 0 }))
        Write-Output ("  actionable_rate_vs_tagged~={0:N1}%  (use snapshot tagged for decisions)" -f $actionableRate)
        Write-Output "  ROUTING_POLICY share of fallback lines=$policyShare"
        if ($tagged -lt 50) {
            Write-Output "  note: tagged < 50 - observe only, avoid routing changes"
        } elseif ($tagged -lt 200) {
            Write-Output "  note: tagged >= 50 - initial review OK"
        } else {
            Write-Output "  note: tagged >= 200 - eligible for retirement-candidate review if stable"
        }
    }
} else {
    Write-Warning "No snapshot fields found on metric lines (unexpected format)."
}
