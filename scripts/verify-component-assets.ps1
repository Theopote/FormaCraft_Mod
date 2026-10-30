param(
    [string]$GradleExecutable,
    [switch]$Offline,
    [string[]]$Tests = @(
        '*ComponentTagIdentityTest', '*ComponentSuiteSelectionTest', '*ComponentBatchPlannerTest', '*ComponentLibraryBatchContextTest', '*ComponentLibraryAcceptanceTest',
        '*ComponentQueryMatchUtilTest', '*ComponentRankerSoftMatchTest',
        '*ComponentRankingEvidenceTest', '*ComponentResolutionReportTest',
        '*CapturedComponentAdaptationTest', '*CapturedComponentDraftTest',
        '*CapturedAxisLayoutTest', '*AsymmetricCaptureEdgesTest', '*CaptureBandLayoutTest'
    )
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$verificationRoot = Join-Path $repoRoot '.gradle/component-asset-verification'
New-Item -ItemType Directory -Path $verificationRoot -Force | Out-Null
foreach ($name in @('settings.gradle', 'gradle.properties', 'build.gradle')) {
    Copy-Item -LiteralPath (Join-Path $repoRoot $name) -Destination (Join-Path $verificationRoot $name) -Force
}
$groovyRoot = $repoRoot.Replace('\', '/').Replace("'", "\'")
$sourceConfig = @"

// Compile live repository sources; isolate Loom's project cache as well as build output.
sourceSets.main.java.setSrcDirs([file('$groovyRoot/src/main/java')])
sourceSets.main.resources.setSrcDirs([file('$groovyRoot/src/main/resources')])
sourceSets.test.java.setSrcDirs([file('$groovyRoot/src/test/java')])
sourceSets.test.resources.setSrcDirs([file('$groovyRoot/src/test/resources')])
tasks.withType(Test).configureEach { workingDir = file('$groovyRoot') }
"@
Add-Content -LiteralPath (Join-Path $verificationRoot 'build.gradle') -Value $sourceConfig -Encoding utf8
if (!$GradleExecutable) { $GradleExecutable = Join-Path $repoRoot 'gradlew.bat' }
$gradleArguments = @('--no-daemon', '-p', $verificationRoot, 'test', '--console=plain')
if ($Offline) { $gradleArguments += '--offline' }
foreach ($testName in $Tests) { $gradleArguments += @('--tests', $testName) }
& $GradleExecutable @gradleArguments
if ($LASTEXITCODE -ne 0) { throw "Component verification failed (Gradle exit $LASTEXITCODE)" }
