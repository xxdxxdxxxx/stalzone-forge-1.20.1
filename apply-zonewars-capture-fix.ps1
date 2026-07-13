<#
  ZoneWars capture fix
  ====================
  Problem: capture points do not progress when a player stands on them because
  insideCaptureCylinder() limits the vertical distance to max(6, radius) blocks.
  Arena points saved at y~63 vs superflat terrain at y~-60 give dy ~ -123, so
  inside=false and progress stays 0.

  Fix: capture zones become full-height cylinders (2D circles on the map, like
  the HUD already shows). Updates ZoneWarsRules, the unit tests, and the
  /zw capturedebug output so debug matches the real rule.

  Usage (run from the repo root, on your feature branch or main):
    powershell -ExecutionPolicy Bypass -File .\apply-zonewars-capture-fix.ps1 -Build -Push
#>
param(
    [string]$RepoPath = ".",
    [string]$Branch = "",
    [switch]$Build,
    [switch]$Push,
    [string]$Remote = "origin",
    [string]$CommitMessage = "Fix capture progress: capture zones are full-height cylinders"
)

$ErrorActionPreference = "Stop"

function Get-Text([string]$Path) {
    return [System.IO.File]::ReadAllText($Path)
}

function Set-Text([string]$Path, [string]$Text) {
    if (Test-Path $Path) {
        Copy-Item $Path "$Path.bak" -Force
    }
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text)
}

# Run git and other native commands through cmd.exe with stderr merged into
# stdout, so PowerShell 5.1 never turns git's status messages into errors.
function Invoke-Native([string]$CommandLine) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    cmd /c "$CommandLine 2>&1" | ForEach-Object { Write-Host $_ }
    $ErrorActionPreference = $eap
    return $LASTEXITCODE
}

# ---------------------------------------------------------------------------
# Embedded file contents
# ---------------------------------------------------------------------------

$ZoneWarsRulesJava = @'
package ru.zonewars.forge;

/** Pure gameplay calculations that can be tested without starting Minecraft. */
public final class ZoneWarsRules {

    private ZoneWarsRules() {
    }

    /**
     * Capture zones are 2D circles on the map. The check deliberately ignores
     * vertical distance: arena points may be saved at a different height than
     * the terrain players actually stand on (superflat worlds, hills, rebuilt
     * maps), and that must never block capturing. The capture cylinder
     * therefore spans the full world height.
     */
    public static boolean insideCaptureCylinder(double dx, double dy, double dz, double radius) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || !Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        return dx * dx + dz * dz <= radius * radius;
    }

    public static final int MAX_MONEY_BALANCE = 1_000_000;

    public static int addMoney(int currentBalance, int amount) {
        long next = Math.max(0L, currentBalance) + Math.max(0L, amount);
        return (int) Math.min(MAX_MONEY_BALANCE, next);
    }

    public static int clampInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static double clampFinite(double value, double fallback, double minimum, double maximum) {
        if (!Double.isFinite(value)) return fallback;
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static double captureRadius(double value) { return clampFinite(value, 9.0, 1.0, 128.0); }
    public static int maxPlayersPerTeam(int value) { return clampInt(value, 1, 100); }
    public static int minPlayersPerTeam(int value, int maxPlayersPerTeam) { return Math.min(maxPlayersPerTeam, clampInt(value, 0, 100)); }
    public static int preparationSeconds(int value) { return clampInt(value, 0, 600); }
    public static int matchSeconds(int value) { return clampInt(value, 60, 21_600); }
    public static int overtimeSeconds(int value) { return clampInt(value, 0, 3_600); }
    public static int endScreenSeconds(int value) { return clampInt(value, 0, 300); }
    public static int captureSeconds(int value) { return clampInt(value, 1, 600); }
    public static int pointsPerSecond(int value) { return clampInt(value, 0, 1_000); }
    public static double coordinate(double value, double fallback) { return clampFinite(value, fallback, -30_000_000.0, 30_000_000.0); }
    public static double yCoordinate(double value, double fallback) { return clampFinite(value, fallback, -2_048.0, 2_048.0); }
    public static double yaw(double value, double fallback) { return clampFinite(value, fallback, -360.0, 360.0); }
    public static double pitch(double value, double fallback) { return clampFinite(value, fallback, -90.0, 90.0); }

}
'@

$ZoneWarsRulesTestJava = @'
package ru.zonewars.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneWarsRulesTest {
    @Test
    void playerInsideHorizontalRadiusIsAccepted() {
        assertTrue(ZoneWarsRules.insideCaptureCylinder(3.0, 0.0, 4.0, 5.0));
    }

    @Test
    void boundaryIsIncluded() {
        assertTrue(ZoneWarsRules.insideCaptureCylinder(5.0, 6.0, 0.0, 5.0));
    }

    @Test
    void playerOutsideHorizontalRadiusIsRejected() {
        assertFalse(ZoneWarsRules.insideCaptureCylinder(5.1, 0.0, 0.0, 5.0));
    }

    @Test
    void moderateTerrainHeightDifferenceIsAccepted() {
        assertTrue(ZoneWarsRules.insideCaptureCylinder(0.0, 5.5, 0.0, 3.0));
    }

    @Test
    void largeTerrainHeightDifferenceIsAccepted() {
        // Regression: superflat terrain at y=-60 vs a point saved at y=63.
        // Capture zones are full-height cylinders, so this must be inside.
        assertTrue(ZoneWarsRules.insideCaptureCylinder(1.8, -123.0, 0.0, 9.0));
        assertTrue(ZoneWarsRules.insideCaptureCylinder(0.0, 6.1, 0.0, 3.0));
    }

    @Test
    void invalidNumbersAndRadiusAreRejected() {
        assertFalse(ZoneWarsRules.insideCaptureCylinder(Double.NaN, 0.0, 0.0, 5.0));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, Double.NaN, 0.0, 5.0));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 0.0, 0.0, 0.0));
        assertFalse(ZoneWarsRules.insideCaptureCylinder(0.0, 0.0, 0.0, -1.0));
    }
    @Test
    void moneyRulesClampAndIgnoreInvalidValues() {
        assertTrue(ZoneWarsRules.addMoney(100, -50) == 100);
        assertTrue(ZoneWarsRules.addMoney(999_990, 50) == ZoneWarsRules.MAX_MONEY_BALANCE);
        assertTrue(ZoneWarsRules.addMoney(-500, 25) == 25);
    }

    @Test
    void captureRadiusIsClampedAndNaNFallsBack() {
        assertTrue(ZoneWarsRules.captureRadius(0.1) == 1.0);
        assertTrue(ZoneWarsRules.captureRadius(999.0) == 128.0);
        assertTrue(ZoneWarsRules.captureRadius(Double.NaN) == 9.0);
    }

    @Test
    void arenaTimersAreClamped() {
        assertTrue(ZoneWarsRules.preparationSeconds(-1) == 0);
        assertTrue(ZoneWarsRules.preparationSeconds(999) == 600);
        assertTrue(ZoneWarsRules.matchSeconds(1) == 60);
        assertTrue(ZoneWarsRules.matchSeconds(99_999) == 21_600);
        assertTrue(ZoneWarsRules.overtimeSeconds(-5) == 0);
        assertTrue(ZoneWarsRules.overtimeSeconds(99_999) == 3_600);
        assertTrue(ZoneWarsRules.captureSeconds(0) == 1);
        assertTrue(ZoneWarsRules.captureSeconds(999) == 600);
    }

    @Test
    void playerLimitsAreClampedAndMinDoesNotExceedMax() {
        assertTrue(ZoneWarsRules.maxPlayersPerTeam(0) == 1);
        assertTrue(ZoneWarsRules.maxPlayersPerTeam(999) == 100);
        assertTrue(ZoneWarsRules.minPlayersPerTeam(-1, 10) == 0);
        assertTrue(ZoneWarsRules.minPlayersPerTeam(20, 10) == 10);
    }

    @Test
    void locationValuesRejectNaNAndClampRanges() {
        assertTrue(ZoneWarsRules.coordinate(Double.NaN, 42.0) == 42.0);
        assertTrue(ZoneWarsRules.coordinate(99_999_999.0, 0.0) == 30_000_000.0);
        assertTrue(ZoneWarsRules.yCoordinate(-99_999.0, 0.0) == -2_048.0);
        assertTrue(ZoneWarsRules.pitch(-999.0, 0.0) == -90.0);
        assertTrue(ZoneWarsRules.yaw(999.0, 0.0) == 360.0);
    }

}
'@

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

$repo = (Resolve-Path $RepoPath).Path
$rulesFile = Join-Path $repo "src/main/java/ru/zonewars/forge/ZoneWarsRules.java"
$testFile  = Join-Path $repo "src/test/java/ru/zonewars/forge/ZoneWarsRulesTest.java"
$forgeFile = Join-Path $repo "src/main/java/ru/zonewars/forge/ZoneWarsForge.java"

if (-not (Test-Path $rulesFile)) {
    throw "ZoneWarsRules.java not found. Run this script from the repo root (or pass -RepoPath)."
}

Push-Location $repo
try {
    $code = Invoke-Native "git rev-parse --is-inside-work-tree"
    if ($code -ne 0) { throw "Not a git repository: $repo" }

    if ($Branch) {
        $code = Invoke-Native "git switch -c $Branch"
        if ($code -ne 0) { $code = Invoke-Native "git switch $Branch" }
        if ($code -ne 0) { throw "Could not create or switch to branch $Branch" }
        Write-Host "[1/4] On branch $Branch" -ForegroundColor Cyan
    } else {
        Write-Host "[1/4] Staying on current branch" -ForegroundColor Cyan
    }

    # --- ZoneWarsRules.java: full-height capture cylinder ---------------------
    Set-Text $rulesFile $ZoneWarsRulesJava
    Write-Host "[2/4] ZoneWarsRules: vertical limit removed (full-height cylinder)" -ForegroundColor Cyan

    # --- ZoneWarsRulesTest.java: updated expectations --------------------------
    Set-Text $testFile $ZoneWarsRulesTestJava
    Write-Host "[3/4] Unit tests updated for the new rule" -ForegroundColor Cyan

    # --- ZoneWarsForge.java: make /zw capturedebug use the same rule -----------
    $forge = Get-Text $forgeFile
    $specific = 'horizontal\s*<=\s*point\.data\(\)\.radius\(\)\s*&&\s*Math\.abs\(dy\)\s*<=\s*Math\.max\(6\.0,\s*point\.data\(\)\.radius\(\)\)'
    $specificCount = [regex]::Matches($forge, $specific).Count
    if ($specificCount -gt 0) {
        $forge = [regex]::Replace($forge, $specific, 'ZoneWarsRules.insideCaptureCylinder(dx, dy, dz, point.data().radius())')
    }
    # Fallback: neutralize any other inline vertical checks of the same idiom.
    $generic = 'Math\.abs\((\w+)\)\s*<=\s*Math\.max\(6\.0,\s*[\w.()]+\)'
    $genericCount = [regex]::Matches($forge, $generic).Count
    if ($genericCount -gt 0) {
        $forge = [regex]::Replace($forge, $generic, 'Double.isFinite($1)')
    }
    Set-Text $forgeFile $forge
    Write-Host "[4/4] ZoneWarsForge: capturedebug aligned ($specificCount specific, $genericCount fallback replacements)" -ForegroundColor Cyan

    # --- Build (compiles + runs the unit tests) -------------------------------
    if ($Build) {
        Write-Host "Building (includes unit tests)..." -ForegroundColor Cyan
        $code = Invoke-Native "gradlew.bat clean build"
        if ($code -ne 0) {
            throw "Build FAILED - nothing was committed. Backups: *.bak next to each patched file."
        }
        Write-Host "Build OK" -ForegroundColor Green
    }

    # --- Commit / push ----------------------------------------------------------
    Get-ChildItem -Path $repo -Recurse -Filter "*.java.bak" | Remove-Item -Force
    Invoke-Native ('git add "src/main/java/ru/zonewars/forge/ZoneWarsRules.java" "src/main/java/ru/zonewars/forge/ZoneWarsForge.java" "src/test/java/ru/zonewars/forge/ZoneWarsRulesTest.java"') | Out-Null
    $code = Invoke-Native ('git commit -m "' + $CommitMessage + '"')
    if ($code -ne 0) { throw "git commit failed (nothing to commit?)" }
    Write-Host "Committed: $CommitMessage" -ForegroundColor Green

    if ($Push) {
        if ($Branch) { $code = Invoke-Native "git push -u $Remote $Branch" } else { $code = Invoke-Native "git push" }
        if ($code -ne 0) { throw "git push failed - check your credentials/remote" }
        Write-Host "Pushed to $Remote" -ForegroundColor Green
    } else {
        Write-Host "Not pushed. To push: git push" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "Done. Verify in game:" -ForegroundColor Green
    Write-Host "  - stand on a point during ACTIVE phase -> progress starts ticking"
    Write-Host "  - /zw capturedebug -> inside=true even with big dy"
}
finally {
    Pop-Location
}
