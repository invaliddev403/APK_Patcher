#!/usr/bin/env python3
"""
citizen_patcher.py

Security research patch tool for the Citizen app (sp0n.citizen).
Injects real credentials and bypasses all premium feature gates via smali patches.

CTF / educational security research — no real payment systems involved.

Supported input formats: .apk  .apks (bundletool)  .apkm (APKMirror)

── TYPICAL USAGE ────────────────────────────────────────────────────────────────

  Patch + install in one step with normal login (most common):
    python3 citizen_patcher.py unpatched_apks/citizen.apkm
    python3 citizen_patcher.py unpatched_apks/citizen.apkm -d <SERIAL>

  Patch with custom credentials (skips the normal login flow):
    python3 citizen_patcher.py unpatched_apks/citizen.apkm --user-id UID --user-token TOKEN

  Patch only — build the APKs but don't install yet:
    python3 citizen_patcher.py unpatched_apks/citizen.apkm --no-install

  Install pre-built APKs (skip the build, just push to device):
    python3 citizen_patcher.py --sideload -p patched/citizen-X.Y.Z/

  First time setup (installs apktool, adb, zipalign, apksigner):
    python3 citizen_patcher.py --setup

── NOTE ─────────────────────────────────────────────────────────────────────────

  --sideload is NOT needed for normal patching. Pass the .apk/.apks/.apkm
  directly as the first argument and the script handles everything automatically.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

DEBUG_KEYSTORE  = Path.home() / ".android" / "debug.keystore"
DEBUG_KS_PASS   = "android"
DEBUG_KEY_ALIAS = "androiddebugkey"


# ── Patch definitions ────────────────────────────────────────────────────────
def build_patches(user_id: str | None, user_token: str | None) -> list:
    """
    Each patch is: (rel_smali_path, method_signature, new_body_lines).
    rel_smali_path is relative to the apktool decompile output directory.
    If the exact path is missing the patcher will search all smali_* dirs.

    If user_id and user_token are both supplied, the patcher hardcodes them
    in SessionManager so the app skips the normal login flow.  If either is
    omitted, the login patches are left out and the unmodified app prompts
    for credentials normally.
    """
    def _ret_true():
        return ["    .locals 1", "    const/4 v0, 0x1", "    return v0"]

    def _ret_false():
        return ["    .locals 1", "    const/4 v0, 0x0", "    return v0"]

    patches = []

    # ── SessionManager — optional credential injection ─────────────────────
    if user_id and user_token:
        patches.extend([
            (
                "smali/sp0n/citizen/data/common/SessionManager.smali",
                ".method public final getUserId()Ljava/lang/String;",
                ["    .locals 1", f'    const-string v0, "{user_id}"', "    return-object v0"],
            ),
            (
                "smali/sp0n/citizen/data/common/SessionManager.smali",
                ".method public final getUserToken()Ljava/lang/String;",
                ["    .locals 1", f'    const-string v0, "{user_token}"', "    return-object v0"],
            ),
            (
                "smali/sp0n/citizen/data/common/SessionManager.smali",
                ".method public final isUserOnboarded()Z",
                _ret_true(),
            ),
        ])

    patches.extend([
        # ── ShowPaywallUseCase — bypass all paywall checks ─────────────────
        (
            "smali/sp0n/citizen/incidentdetail/ShowPaywallUseCase.smali",
            ".method public final a(Lsp0n/citizen/incidentdetail/ShowPaywallUseCase$SubscriptionFeature;)Z",
            _ret_true(),
        ),
        (
            "smali/sp0n/citizen/incidentdetail/ShowPaywallUseCase.smali",
            ".method public final c()Z",
            _ret_true(),
        ),
        (
            "smali/sp0n/citizen/incidentdetail/ShowPaywallUseCase.smali",
            ".method public final d()Z",
            _ret_true(),
        ),
        # ── PrivateUser — report all premium tiers as active ───────────────
        (
            "smali_classes5/sp0n/citizen/domain/models/user/PrivateUser.smali",
            ".method public final isPlusActive()Z",
            _ret_true(),
        ),
        (
            "smali_classes5/sp0n/citizen/domain/models/user/PrivateUser.smali",
            ".method public final isProtectActive()Z",
            _ret_true(),
        ),
        (
            "smali_classes5/sp0n/citizen/domain/models/user/PrivateUser.smali",
            ".method public final isProtectActiveOrInSetup()Z",
            _ret_true(),
        ),
        # ── FreeEntitlementCampaign — always premium ───────────────────────
        (
            "smali_classes5/sp0n/citizen/domain/models/campaign/FreeEntitlementCampaign.smali",
            ".method public final isPremium()Z",
            _ret_true(),
        ),
        # ── PrivateUser.isPaid — MainViewModel routing gate ────────────────
        (
            "smali_classes5/sp0n/citizen/domain/models/user/PrivateUser.smali",
            ".method public final isPaid()Z",
            _ret_true(),
        ),
    ])
    return patches


# ── Tool / process helpers ───────────────────────────────────────────────────
def run(cmd: list, cwd: Path = None, check: bool = True) -> subprocess.CompletedProcess:
    print(f"  $ {' '.join(str(c) for c in cmd)}")
    return subprocess.run(cmd, cwd=cwd, check=check, stdin=subprocess.DEVNULL)


def check_tools():
    required = ["apktool", "adb", "zipalign", "apksigner", "zip"]
    missing = [t for t in required if shutil.which(t) is None]
    if missing:
        print(f"[!] Missing required tools: {', '.join(missing)}")
        print("    Run:  python3 citizen_patcher.py --setup")
        sys.exit(1)
    if not DEBUG_KEYSTORE.exists():
        print(f"[!] Debug keystore not found: {DEBUG_KEYSTORE}")
        print("    Run:  python3 citizen_patcher.py --setup")
        sys.exit(1)


# ── Tool setup (--setup flag) ─────────────────────────────────────────────────
def _find_sdk_tool(tool_name: str):
    sdk_candidates = [
        Path(os.environ.get("ANDROID_HOME", "")),
        Path(os.environ.get("ANDROID_SDK_ROOT", "")),
        Path.home() / "Library" / "Android" / "sdk",
        Path("/opt/homebrew/share/android-commandlinetools"),
        Path("/usr/local/share/android-sdk"),
        Path("/opt/android-sdk"),
    ]
    for sdk in sdk_candidates:
        if not sdk.exists():
            continue
        matches = sorted(sdk.glob(f"build-tools/*/{tool_name}"),
                         key=lambda p: p.parent.name, reverse=True)
        if matches:
            return matches[0]
    return None


def _find_sdkmanager():
    if shutil.which("sdkmanager"):
        return Path(shutil.which("sdkmanager"))
    candidates = [
        Path(os.environ.get("ANDROID_HOME", "")),
        Path(os.environ.get("ANDROID_SDK_ROOT", "")),
        Path.home() / "Library" / "Android" / "sdk",
        Path("/opt/homebrew/share/android-commandlinetools"),
    ]
    for base in candidates:
        if not base.exists():
            continue
        for pattern in ("cmdline-tools/latest/bin/sdkmanager",
                        "cmdline-tools/*/bin/sdkmanager",
                        "tools/bin/sdkmanager"):
            matches = list(base.glob(pattern))
            if matches:
                return matches[0]
    return None


def _link_sdk_tool(tool_name: str) -> bool:
    sdk_path = _find_sdk_tool(tool_name)
    if not sdk_path:
        return False
    for bin_dir in (Path("/usr/local/bin"), Path("/opt/homebrew/bin")):
        if not bin_dir.exists():
            continue
        dest = bin_dir / tool_name
        try:
            if dest.is_symlink() or dest.exists():
                dest.unlink()
            dest.symlink_to(sdk_path)
            print(f"    Linked {sdk_path} → {dest}")
            return True
        except PermissionError:
            try:
                subprocess.run(["sudo", "ln", "-sf", str(sdk_path), str(dest)], check=True)
                print(f"    Linked {sdk_path} → {dest}  (sudo)")
                return True
            except subprocess.CalledProcessError:
                continue
    return False


def setup_tools():
    """Install required tools (apktool, adb, zipalign, apksigner) and create debug keystore."""
    import platform

    system = platform.system()
    print("\n[*] Setting up tools...")

    if system == "Darwin":
        if not shutil.which("brew"):
            print("  [*] Installing Homebrew...")
            subprocess.run(
                ["/bin/bash", "-c",
                 "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"],
                check=True,
            )

        if not shutil.which("apktool"):
            print("  [*] brew install apktool  (pulls in openjdk)...")
            subprocess.run(["brew", "install", "apktool"], check=True)
        else:
            print("  [✓] apktool")

        if not shutil.which("adb"):
            print("  [*] brew install android-platform-tools...")
            subprocess.run(["brew", "install", "android-platform-tools"], check=True)
        else:
            print("  [✓] adb")

        for tool in ("zipalign", "apksigner"):
            if shutil.which(tool):
                print(f"  [✓] {tool}")
                continue
            print(f"  [~] {tool} not in PATH — searching Android SDK...")
            if _link_sdk_tool(tool):
                print(f"  [✓] {tool}")
                continue
            print(f"  [*] Installing Android command-line tools (needed for {tool})...")
            subprocess.run(
                ["brew", "install", "--cask", "android-commandlinetools"],
                check=False,
            )
            sdkmgr = _find_sdkmanager()
            if sdkmgr:
                env = os.environ.copy()
                java_home = subprocess.run(
                    ["/usr/libexec/java_home"], capture_output=True, text=True
                ).stdout.strip()
                if java_home:
                    env["JAVA_HOME"] = java_home
                subprocess.run(
                    [str(sdkmgr), "--licenses"],
                    input="y\n" * 10, text=True, env=env, check=False,
                )
                subprocess.run([str(sdkmgr), "build-tools;34.0.0"], env=env, check=False)
            if _link_sdk_tool(tool):
                print(f"  [✓] {tool}")
            else:
                print(f"  [!] Could not install {tool} automatically.")
                print(f"      Install Android Studio or run: sdkmanager 'build-tools;34.0.0'")

    elif system == "Linux":
        print("  [*] apt-get install tools...")
        subprocess.run(["sudo", "apt-get", "update", "-q"], check=True)
        subprocess.run([
            "sudo", "apt-get", "install", "-y",
            "default-jdk", "apktool",
            "android-sdk-platform-tools",
            "zipalign", "apksigner",
        ], check=True)

    else:
        print(f"  [!] Unsupported platform: {system}")
        print("      Install manually: apktool, adb, zipalign, apksigner, JDK")
        sys.exit(1)

    if DEBUG_KEYSTORE.exists():
        print(f"  [✓] Debug keystore: {DEBUG_KEYSTORE}")
    else:
        keytool = shutil.which("keytool")
        if not keytool:
            print("  [!] keytool not found — install a JDK to create the debug keystore")
        else:
            print(f"  [*] Creating debug keystore at {DEBUG_KEYSTORE}...")
            DEBUG_KEYSTORE.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([
                "keytool", "-genkey", "-v",
                "-keystore", str(DEBUG_KEYSTORE),
                "-alias", DEBUG_KEY_ALIAS,
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000",
                "-storepass", DEBUG_KS_PASS,
                "-keypass",   DEBUG_KS_PASS,
                "-dname", "CN=Android Debug,O=Android,C=US",
            ], check=True)
            print(f"  [✓] Debug keystore created")

    print("\n[✓] Setup complete — run the patcher again without --setup to build.")


# ── APK / archive handling ───────────────────────────────────────────────────
def extract_input(input_path: Path, work_dir: Path) -> tuple:
    """
    Returns (base_apk: Path, splits: list[Path]).
    For a plain .apk the splits list is empty.
    """
    if input_path.suffix.lower() == ".apk":
        return input_path, []

    extract_dir = work_dir / "extracted"
    extract_dir.mkdir()
    print(f"  Extracting {input_path.suffix.upper()} archive...")
    with zipfile.ZipFile(input_path) as zf:
        zf.extractall(extract_dir)

    all_apks = sorted(extract_dir.rglob("*.apk"), key=lambda p: p.stat().st_size, reverse=True)
    if not all_apks:
        print("[!] No APKs found inside the archive.")
        sys.exit(1)

    base_apk = next(
        (a for a in all_apks if a.name in ("base.apk", "base-master.apk")),
        None,
    )
    if base_apk is None:
        base_apk = all_apks[0]
        print(f"  [~] Could not identify base APK; using largest: {base_apk.name}")

    splits = [a for a in all_apks if a != base_apk]
    print(f"  Base : {base_apk.name}  ({base_apk.stat().st_size // 1024} KB)")
    print(f"  Splits: {[s.name for s in splits]}")
    return base_apk, splits


def get_apk_info(decompile_dir: Path, source_apk: Path = None) -> tuple:
    """Read package name and version from apktool's decompile output.

    When apktool is run with -r/--no-res, AndroidManifest.xml remains binary,
    so package/version are read from apktool.yml and aapt2 badging of the
    original APK as a fallback.
    """
    pkg_name = "sp0n.citizen"
    ver_name = "0.0"

    manifest = decompile_dir / "AndroidManifest.xml"
    if manifest.exists():
        try:
            content = manifest.read_text("utf-8")
            m = re.search(r'package="([^"]+)"', content)
            if m:
                pkg_name = m.group(1)
        except UnicodeDecodeError:
            # Binary manifest — try aapt2 on the original APK
            if source_apk:
                result = subprocess.run(
                    ["aapt2", "dump", "badging", str(source_apk)],
                    capture_output=True, text=True, check=False,
                )
                m = re.search(r"package: name='([^']+)'", result.stdout)
                if m:
                    pkg_name = m.group(1)
                m = re.search(r"versionName='([^']+)'", result.stdout)
                if m:
                    ver_name = m.group(1)

    yml = decompile_dir / "apktool.yml"
    if yml.exists():
        content = yml.read_text("utf-8")
        m = re.search(r"versionName:\s*(.+)", content)
        if m:
            ver_name = m.group(1).strip().strip("'\"")

    return pkg_name, ver_name


def get_app_label(decompile_dir: Path) -> str | None:
    """
    Read the human-readable app name from the decompiled manifest.
    Resolves @string/ references via res/values/strings.xml.
    Returns None if the label can't be determined.
    """
    manifest = decompile_dir / "AndroidManifest.xml"
    if not manifest.exists():
        return None

    content = manifest.read_text("utf-8")
    m = re.search(r'<application[^>]+android:label="([^"]+)"', content, re.DOTALL)
    if not m:
        return None

    label = m.group(1).strip()

    if label.startswith("@string/"):
        string_name = label[len("@string/"):]
        strings_xml = decompile_dir / "res" / "values" / "strings.xml"
        if not strings_xml.exists():
            return None
        sc = strings_xml.read_text("utf-8")
        sm = re.search(rf'<string name="{re.escape(string_name)}"[^>]*>([^<]+)<', sc)
        if not sm:
            return None
        label = sm.group(1).strip()

    # Sanitise for use as a directory name
    label = re.sub(r"[^\w\s-]", "", label)   # remove special chars
    label = re.sub(r"\s+", "-", label)        # spaces → hyphens
    return label.lower() if label else None


# ── Smali patching ───────────────────────────────────────────────────────────
def patch_smali_method(smali_path: Path, method_sig: str, new_body: list) -> bool:
    """
    Replace a method body in a smali file.
    Returns True if the method signature was found and replaced.
    """
    lines = smali_path.read_text("utf-8").splitlines(keepends=True)
    result = []
    i = 0
    patched = False

    while i < len(lines):
        # Compare against full .method line (strip trailing whitespace only)
        if lines[i].rstrip("\r\n\t ") == method_sig:
            result.append(lines[i])       # emit ".method ..." line
            i += 1
            # skip the original body
            while i < len(lines) and lines[i].strip() != ".end method":
                i += 1
            # write replacement body
            for body_line in new_body:
                result.append(body_line + "\n")
            # emit ".end method"
            if i < len(lines):
                result.append(lines[i])
                i += 1
            patched = True
        else:
            result.append(lines[i])
            i += 1

    if patched:
        smali_path.write_text("".join(result), "utf-8")
    return patched


def patch_manifest(decompile_dir: Path) -> None:
    """Export all sp0n.citizen.debug and com.superwall.sdk.debug activities."""
    manifest_path = decompile_dir / "AndroidManifest.xml"
    if not manifest_path.exists():
        print("  [!] AndroidManifest.xml not found — skipping manifest patches")
        return

    DEBUG_PATTERNS = ["com.superwall.sdk.debug", "sp0n.citizen.debug"]

    lines = manifest_path.read_text("utf-8").splitlines(keepends=True)
    result = []
    exported_count = 0

    for line in lines:
        stripped = line.rstrip("\r\n")
        if "<activity" in stripped and 'android:exported=' not in stripped:
            for pat in DEBUG_PATTERNS:
                if pat in stripped:
                    line = line.replace("<activity ", "<activity android:exported=\"true\" ", 1)
                    exported_count += 1
                    break
        result.append(line)

    manifest_path.write_text("".join(result), "utf-8")
    print(f"  [OK] Exported {exported_count} debug activities")


def patch_shake_gate(decompile_dir: Path) -> None:
    """Enable shake-to-open-debug by removing the 'citizenProd' build variant gate in BaseActivity2."""
    target = next(decompile_dir.rglob("ny0.smali"), None)
    if not target:
        print("  [!] shake gate: ny0.smali not found — skipping")
        return
    text = target.read_text("utf-8")
    # Gate: `"citizenProd".contains("Dev")` → always false in prod → branch skips shake init.
    # Flip if-eqz → if-nez so the skip never fires.
    old = "    if-eqz v0, :cond_4"
    new = "    if-nez v0, :cond_4"
    if old not in text:
        print("  [!] shake gate: branch not found in ny0.smali — skipping")
        return
    target.write_text(text.replace(old, new, 1), "utf-8")
    print("  [OK] ny0.smali :: shake gate removed (shake phone → DebugMainActivity)")


def patch_medium_debug_trigger(decompile_dir: Path) -> None:
    """Redirect the Medium social icon tap on MyProfile screen to launch DebugMainActivity."""
    target_path = "smali_classes5/sp0n/citizen/profile/myprofile/ui/b$a$a.smali"
    target = decompile_dir / target_path
    if not target.exists():
        found = list(decompile_dir.rglob("b$a$a.smali"))
        target = next((p for p in found if "myprofile" in str(p)), None)
    if not target:
        print("  [!] medium debug trigger: myprofile/ui/b$a$a.smali not found — skipping")
        return

    text = target.read_text("utf-8")

    old = (
        "    check-cast v0, Lsp0n/citizen/profile/myprofile/ui/c$o;\n"
        "\n"
        "    .line 358\n"
        "    .line 359\n"
        "    iget-object p1, v0, Lsp0n/citizen/profile/myprofile/ui/c$o;->a:Ljava/lang/String;\n"
        "\n"
        "    .line 360\n"
        "    .line 361\n"
        "    new-instance v0, Landroid/content/Intent;\n"
        "\n"
        "    .line 362\n"
        "    .line 363\n"
        "    const-string v1, \"android.intent.action.VIEW\"\n"
        "\n"
        "    .line 364\n"
        "    .line 365\n"
        "    invoke-static {p1}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;\n"
        "\n"
        "    .line 366\n"
        "    .line 367\n"
        "    .line 368\n"
        "    move-result-object p1\n"
        "\n"
        "    .line 369\n"
        "    invoke-direct {v0, v1, p1}, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V\n"
        "\n"
        "    .line 370\n"
        "    .line 371\n"
        "    .line 372\n"
        "    invoke-virtual {p0, v0}, Landroidx/fragment/app/Fragment;->h0(Landroid/content/Intent;)V\n"
        "\n"
        "    .line 373\n"
        "    .line 374\n"
        "    .line 375\n"
        "    goto/16 :goto_0"
    )

    new = (
        "    check-cast v0, Lsp0n/citizen/profile/myprofile/ui/c$o;\n"
        "\n"
        "    .line 358\n"
        "    .line 359\n"
        "    iget-object p1, v0, Lsp0n/citizen/profile/myprofile/ui/c$o;->a:Ljava/lang/String;\n"
        "\n"
        "    const-string v0, \"medium.com/citizen\"\n"
        "    invoke-virtual {p1, v0}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z\n"
        "    move-result v0\n"
        "    if-eqz v0, :cond_open_url_patch\n"
        "\n"
        "    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->a0()Landroid/content/Context;\n"
        "    move-result-object v0\n"
        "    new-instance v1, Landroid/content/Intent;\n"
        "    const-class v2, Lsp0n/citizen/debug/DebugMainActivity;\n"
        "    invoke-direct {v1, v0, v2}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V\n"
        "    invoke-virtual {p0, v1}, Landroidx/fragment/app/Fragment;->h0(Landroid/content/Intent;)V\n"
        "    goto/16 :goto_0\n"
        "\n"
        "    :cond_open_url_patch\n"
        "    new-instance v0, Landroid/content/Intent;\n"
        "\n"
        "    .line 362\n"
        "    .line 363\n"
        "    const-string v1, \"android.intent.action.VIEW\"\n"
        "\n"
        "    .line 364\n"
        "    .line 365\n"
        "    invoke-static {p1}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;\n"
        "\n"
        "    .line 366\n"
        "    .line 367\n"
        "    .line 368\n"
        "    move-result-object p1\n"
        "\n"
        "    .line 369\n"
        "    invoke-direct {v0, v1, p1}, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V\n"
        "\n"
        "    .line 370\n"
        "    .line 371\n"
        "    .line 372\n"
        "    invoke-virtual {p0, v0}, Landroidx/fragment/app/Fragment;->h0(Landroid/content/Intent;)V\n"
        "\n"
        "    .line 373\n"
        "    .line 374\n"
        "    .line 375\n"
        "    goto/16 :goto_0"
    )

    if old not in text:
        print("  [!] medium debug trigger: c$o handler block not found in b$a$a.smali — skipping")
        return

    target.write_text(text.replace(old, new, 1), "utf-8")
    print("  [OK] b$a$a.smali :: Medium social icon → DebugMainActivity")


def apply_patches(decompile_dir: Path, patches: list) -> None:
    print("\n[*] Applying smali patches...")
    ok_count = 0
    fail_count = 0

    for rel_path, method_sig, new_body in patches:
        smali_path = decompile_dir / rel_path

        # If exact relative path not found, search all smali_* dirs by filename
        if not smali_path.exists():
            fname = Path(rel_path).name
            found = list(decompile_dir.rglob(fname))
            if found:
                smali_path = found[0]
                print(f"  [~] Relocated {fname} → {smali_path.relative_to(decompile_dir)}")
            else:
                print(f"  [!] FILE NOT FOUND: {rel_path}")
                fail_count += 1
                continue

        ok = patch_smali_method(smali_path, method_sig, new_body)
        label = smali_path.relative_to(decompile_dir)
        short_sig = method_sig[len(".method public final "):]
        if ok:
            print(f"  [OK] {label} :: {short_sig}")
            ok_count += 1
        else:
            print(f"  [!] METHOD NOT FOUND in {label} :: {short_sig}")
            fail_count += 1

    print(f"\n  Patched {ok_count} methods, {fail_count} not found.")
    if fail_count > 0:
        answer = input("  Continue anyway? [y/N] ").strip().lower()
        if answer != "y":
            sys.exit(1)


# ── APK build pipeline ───────────────────────────────────────────────────────
def dex_only_swap(original_base: Path, rebuilt_apk: Path, work_dir: Path) -> Path:
    """
    Extract patched DEX from rebuilt_apk and inject into a copy of original_base,
    preserving the original AndroidManifest.xml and all other original assets.
    Returns the path to the zipaligned (unsigned) APK.
    """
    print("\n[*] DEX-only swap (preserving original manifest + resources.arsc)...")

    # Extract patched DEX files from rebuilt APK
    dex_dir = work_dir / "dex"
    dex_dir.mkdir()
    with zipfile.ZipFile(rebuilt_apk) as zf:
        dex_names = [n for n in zf.namelist() if re.match(r"classes\d*\.dex$", n)]
        for name in dex_names:
            zf.extract(name, dex_dir)
    print(f"  DEX extracted: {dex_names}")

    # Work on a copy of the original base APK
    work_apk = work_dir / "base_work.apk"
    shutil.copy2(original_base, work_apk)

    # Overwrite DEX entries in the copy
    run(["zip", "-u", str(work_apk)] + dex_names, cwd=dex_dir)

    # Fix resources.arsc — must be stored uncompressed (ZIP_STORED) on Android 11+
    res_dir = work_dir / "res_tmp"
    res_dir.mkdir()
    with zipfile.ZipFile(work_apk) as zf:
        if "resources.arsc" in zf.namelist():
            info = zf.getinfo("resources.arsc")
            if info.compress_type != zipfile.ZIP_STORED:
                print("  Fixing resources.arsc → ZIP_STORED...")
                (res_dir / "resources.arsc").write_bytes(zf.read("resources.arsc"))
                run(["zip", "-d", str(work_apk), "resources.arsc"])
                run(["zip", "-0", "-u", str(work_apk), "resources.arsc"], cwd=res_dir)
            else:
                print("  resources.arsc already ZIP_STORED — no change needed.")

    # 4-byte align
    aligned_apk = work_dir / "base_aligned.apk"
    run(["zipalign", "-f", "-p", "4", str(work_apk), str(aligned_apk)])
    return aligned_apk


def sign_apk(input_apk: Path, output_apk: Path) -> None:
    run([
        "apksigner", "sign",
        "--ks", str(DEBUG_KEYSTORE),
        "--ks-pass", f"pass:{DEBUG_KS_PASS}",
        "--ks-key-alias", DEBUG_KEY_ALIAS,
        "--out", str(output_apk),
        str(input_apk),
    ])


# ── Device / ADB helpers ─────────────────────────────────────────────────────
def get_connected_devices() -> list:
    """Returns list of (serial, display_name) for authorized ADB devices."""
    result = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=False)
    serials = [
        line.split()[0]
        for line in result.stdout.splitlines()[1:]
        if len(line.split()) == 2 and line.split()[1] == "device"
    ]
    devices = []
    for serial in serials:
        def getprop(prop):
            return subprocess.run(
                ["adb", "-s", serial, "shell", "getprop", prop],
                capture_output=True, text=True, check=False
            ).stdout.strip()
        mfr   = getprop("ro.product.manufacturer")
        model = getprop("ro.product.model")
        display = f"{mfr} {model}".strip() or serial
        devices.append((serial, display))
    return devices


def select_device(devices: list, preselect: str = None) -> str:
    """
    Return the serial of the device to install on.
    preselect can be a serial number, a 1-based index string ("1", "2", …),
    or a case-insensitive substring of the device name.
    """
    if len(devices) == 1:
        serial, name = devices[0]
        print(f"  Device: {name} ({serial})")
        return serial

    if preselect:
        # Try index first ("1", "2", …)
        if preselect.isdigit():
            idx = int(preselect) - 1
            if 0 <= idx < len(devices):
                serial, name = devices[idx]
                print(f"  Device: {name} ({serial})")
                return serial
        # Try exact serial match
        for serial, name in devices:
            if serial == preselect:
                print(f"  Device: {name} ({serial})")
                return serial
        # Try name substring
        needle = preselect.lower()
        for serial, name in devices:
            if needle in name.lower():
                print(f"  Device: {name} ({serial})")
                return serial
        print(f"  [!] --device '{preselect}' did not match any connected device; falling back to prompt.")

    print("\n  Multiple devices connected — choose one:")
    for i, (serial, name) in enumerate(devices, 1):
        print(f"    {i}. {name} ({serial})")
    while True:
        try:
            choice = int(input("  Enter number: "))
            if 1 <= choice <= len(devices):
                serial, name = devices[choice - 1]
                print(f"  Selected: {name} ({serial})")
                return serial
        except (ValueError, KeyboardInterrupt):
            pass
        print(f"  Please enter 1–{len(devices)}.")


def install_apks(serial: str, base_apk: Path, splits: list) -> None:
    if splits:
        run(["adb", "-s", serial, "install-multiple", "-r", str(base_apk)]
            + [str(s) for s in splits])
    else:
        run(["adb", "-s", serial, "install", "-r", str(base_apk)])


def sideload_from_path(apk_dir: Path, device: str = None) -> None:
    """Install all APKs from an already-patched output directory."""
    if not apk_dir.exists() or not apk_dir.is_dir():
        print(f"[!] Directory not found: {apk_dir}")
        sys.exit(1)

    all_apks = sorted(apk_dir.glob("*.apk"))
    if not all_apks:
        print(f"[!] No .apk files found in {apk_dir}")
        sys.exit(1)

    # base.apk must be first; everything else follows in alphabetical order
    base = next((a for a in all_apks if a.name == "base.apk"), None)
    if base is None:
        base = all_apks[0]
        print(f"  [~] No base.apk found; using {base.name} as base")
    splits = [a for a in all_apks if a != base]

    print(f"\n[*] Sideload from: {apk_dir.resolve()}")
    print(f"  base   : {base.name}  ({base.stat().st_size / 1024 / 1024:.1f} MB)")
    for s in splits:
        print(f"  split  : {s.name}  ({s.stat().st_size / 1024 / 1024:.1f} MB)")

    devices = get_connected_devices()
    if not devices:
        print("\n[!] No ADB devices connected.")
        sys.exit(1)

    serial = select_device(devices, preselect=device)
    print("\n[*] Installing...")
    install_apks(serial, base, splits)
    print("\n[✓] Install complete.")


# ── Entry point ──────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(
        description="Citizen app smali patcher — premium bypass with optional credential injection",
        epilog=(
            "examples:\n"
            "  %(prog)s unpatched_apks/citizen.apkm                       # patch + install (normal login)\n"
            "  %(prog)s unpatched_apks/citizen.apkm -d SERIAL             # specify device\n"
            "  %(prog)s unpatched_apks/citizen.apkm --no-install          # build only\n"
            "  %(prog)s unpatched_apks/citizen.apkm --user-id U --user-token T  # skip login\n"
            "  %(prog)s --sideload -p patched/citizen-X.Y.Z/              # re-install only\n"
            "  %(prog)s --setup                                            # install tools\n"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("input", type=Path, nargs="?",
                        help="Input file: .apk / .apks / .apkm  (omit when using --sideload)")
    parser.add_argument("--user-id", default=None,
                        help="Citizen user ID to hardcode (requires --user-token)")
    parser.add_argument("--user-token", default=None,
                        help="Citizen JWT token to hardcode (requires --user-id)")
    parser.add_argument("--no-install", action="store_true",
                        help="Build only; skip ADB install")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).parent / "patched",
                        help="Output base directory (default: <script-dir>/patched/)")
    parser.add_argument("-s", "--sideload", action="store_true",
                        help="Sideload APKs from an existing patched directory (skip build)")
    parser.add_argument("-p", "--path", type=Path, dest="sideload_path",
                        help="Path to directory containing patched APKs (used with --sideload)")
    parser.add_argument("-d", "--device", default=None,
                        help="Target device: serial, 1-based index, or name substring (e.g. 'Pixel 3')")
    parser.add_argument("--setup", action="store_true",
                        help="Install required tools (apktool, adb, zipalign, apksigner) and create debug keystore")
    args = parser.parse_args()

    if args.setup:
        setup_tools()
        return

    # ── Sideload-only mode ────────────────────────────────────────────────────
    if args.sideload:
        if args.sideload_path is None:
            parser.error("--sideload requires --path / -p <directory>")
        sideload_from_path(args.sideload_path.resolve(), device=args.device)
        return

    # ── Patch + (optionally) install mode ────────────────────────────────────
    if args.input is None:
        parser.error("Provide an input file (.apk/.apks/.apkm) or use --sideload --path")

    # Check suffix on the user-supplied path (before resolve) so symlinks with
    # a .apkm extension aren't rejected because their target lacks an extension.
    if args.input.suffix.lower() not in (".apk", ".apks", ".apkm"):
        print(f"[!] Unsupported format: {args.input.suffix}")
        sys.exit(1)
    input_path = args.input.resolve()
    if not input_path.exists():
        print(f"[!] File not found: {input_path}")
        sys.exit(1)

    check_tools()

    if bool(args.user_id) != bool(args.user_token):
        parser.error("--user-id and --user-token must be provided together")

    patches = build_patches(args.user_id, args.user_token)

    with tempfile.TemporaryDirectory(prefix="citizen_patch_") as tmp:
        work_dir = Path(tmp)

        # 1. Extract input archive
        print(f"\n[*] Input: {input_path.name}")
        base_apk, split_apks = extract_input(input_path, work_dir)

        # 2. Decompile base APK (full decode so the manifest is text and
        #    smali is available; the res folder is removed before rebuild).
        decompile_dir = work_dir / "decompiled"
        print("\n[*] Decompiling with apktool...")
        run(["apktool", "d", str(base_apk), "-o", str(decompile_dir), "-f"])

        # 3. Read package info from decompile output
        pkg_name, version = get_apk_info(decompile_dir, base_apk)
        app_label = get_app_label(decompile_dir) or pkg_name
        folder_name = f"{app_label}-{version}"
        print(f"\n[*] Package : {pkg_name}")
        print(f"    Label   : {app_label}")
        print(f"    Version : {version}")
        print(f"    Output  : {args.output_dir / folder_name}/")

        # 4. Apply smali patches
        apply_patches(decompile_dir, patches)

        # 4b. Medium icon → DebugMainActivity in-app trigger
        print("\n[*] Patching Medium social icon → DebugMainActivity...")
        patch_medium_debug_trigger(decompile_dir)

        # 4c. Drop decoded resources before rebuild.  apktool/aapt2 on this APK
        #    chokes on animated-vector drawable filenames that start with '$'.
        #    The final APK uses the original base APK's resources anyway (DEX-only
        #    swap), so the decoded res folder is only needed for label lookup.
        res_dir = decompile_dir / "res"
        if res_dir.exists():
            shutil.rmtree(res_dir)
            print("\n[*] Removed decoded res/ to avoid aapt2 $filename errors")

        # 5. Rebuild with apktool
        rebuilt_apk = work_dir / "rebuilt.apk"
        print("\n[*] Rebuilding APK with apktool...")
        run(["apktool", "b", str(decompile_dir), "-o", str(rebuilt_apk), "--use-aapt2"])

        # 6. DEX-only swap into original base (preserves resources.arsc integrity)
        aligned_apk = dex_only_swap(base_apk, rebuilt_apk, work_dir)

        # 7. Sign base APK
        print("\n[*] Signing base APK...")
        signed_base = work_dir / "base-signed.apk"
        sign_apk(aligned_apk, signed_base)

        # 8. Re-sign splits from originals with the same debug key
        signed_splits = []
        if split_apks:
            print("\n[*] Re-signing split APKs...")
            for split in split_apks:
                out = work_dir / f"signed_{split.name}"
                sign_apk(split, out)
                signed_splits.append((split.name, out))

        # 9. Copy everything to persistent output directory
        out_dir = args.output_dir / folder_name
        out_dir.mkdir(parents=True, exist_ok=True)

        final_base = out_dir / "base.apk"
        shutil.copy2(signed_base, final_base)

        final_splits = []
        for orig_name, signed_path in signed_splits:
            dest = out_dir / orig_name
            shutil.copy2(signed_path, dest)
            final_splits.append(dest)

        print(f"\n[✓] Saved to: {out_dir.resolve()}")
        for f in [final_base] + final_splits:
            print(f"    {f.name}  ({f.stat().st_size / 1024 / 1024:.1f} MB)")

        # 10. Install (optional)
        if not args.no_install:
            print("\n[*] Checking for connected devices...")
            devices = get_connected_devices()
            if not devices:
                print("  No ADB devices connected — skipping install.")
                print(f"  To install manually:\n"
                      f"    adb install-multiple {' '.join(str(f) for f in [final_base] + final_splits)}")
            else:
                serial = select_device(devices, preselect=args.device)
                print("\n[*] Installing...")
                install_apks(serial, final_base, final_splits)
                print("\n[✓] Install complete.")
        else:
            print(f"\n  To install later:\n"
                  f"    adb install-multiple {' '.join(str(f) for f in [final_base] + final_splits)}")


if __name__ == "__main__":
    main()
