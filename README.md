# APK Patcher

Native Kotlin Android application that downloads, patches, signs, and installs Citizen APK packages on-device. It supports APK, APKM, APKS, and XAPK inputs and requires no computer during normal use.

## Using the app

### Requirements and installation

- A device running Android 8.0 (API 26) or newer.
- The APK Patcher APK installed on the device. The debug build is at `app/build/outputs/apk/debug/app-debug.apk` after building.
- Enough free storage for the download and several temporary APK copies. Large split packages can require several hundred megabytes.
- Permission to install unknown apps. Android requests this when installation first begins; enable it for APK Patcher, then return to the app.
- Notification permission is recommended so background progress and completion remain visible.

Only patch applications you are authorized to modify. Download APKs from sources you trust. Re-signing changes the application's cryptographic signature and can affect updates, accounts, and local data.

The included patch pack supports **Citizen `0.1316.0`**. Other applications and Citizen versions require a compatible pack; the app will not silently apply Citizen patches to another package.

### 1. Obtain the application package

Choose one of these methods:

- **APKMirror, APKPure, or APKCombo:** tap the source, select the Citizen version and device architecture in the built-in browser, and start its download. Complete any browser challenge presented by the source. The download then continues in APK Patcher.
- **Direct URL:** paste an HTTPS URL that returns an APK, APKM, APKS, or XAPK file, then tap **Download from URL**. A page URL is not necessarily a file download URL.
- **Existing file:** tap **Choose a local file** and select an APK, APKM, APKS, or XAPK package.

For split applications, use the complete APKM/APKS/XAPK package or the base APK together with every required split. Do not select only a configuration split. The app validates the archive and selects the base APK automatically.

### Update patch definitions from Morphe

After selecting an APK, tap **Update patch definitions from Morphe** to derive definitions from the latest upstream Citizen source and the selected APK:

1. The app downloads the current GitHub commit ID, `CitizenFingerprints.kt`, and `CitizenUnlockProPatch.kt` from `rushiranpise/morphe-patches` on `main`.
2. It scans the selected APK's DEX files and applies exact matches and unambiguous class/name/return-compatible signature adjustments.
3. It reports applied targets, adjusted signatures, missing targets, unsupported operations, and retained built-in targets.
4. Review that report before patching. Missing or unsupported entries require maintainer review; they are never guessed.
5. The generated commit-specific pack is stored in the app's private `files/packs/<package>/<version>/` directory and selected on the next compatible patch. If no downloaded pack is compatible, the built-in extended pack is used.

Updating definitions does not patch or install the APK. Generation still uses only the declarative operation allowlist—never arbitrary Kotlin or downloaded executable instructions. Following `main` means upstream changes affect generated output, so the commit ID and review report matter. The app does not silently update in the background.

### 2. Review the patch options

- **Install automatically after patching:** when enabled, Android's installation screen appears after patching. If the app is closed, reopen it after completion to continue. When disabled, use **Install patched APK** later.
- **User ID (optional):** an existing Citizen user ID to embed in the patched application.
- **User token (optional):** the authentication token associated with that user ID. This is not the account password.

Leave both credential fields blank to keep Citizen's normal login flow. To inject an existing session, enter both values; entering only one is rejected. Credentials are used only while patching and are not included in workflow state or the JSON patch pack. They are embedded in the patched APK by design, so do not share that APK.

### 3. Patch and install

1. Confirm the selected filename and size.
2. Tap **Patch & install**.
3. Wait for archive extraction, DEX patching, rebuilding, and signing. You can leave the app; a foreground notification shows progress.
4. Review Android's installation prompt and tap **Install** or **Update**. Keep APK Patcher open until Android reports the result.
5. After a successful installation, use **Open Citizen**.

The output is saved under the app's external-files directory, for example:

```text
Android/data/dev.apkpatcher/files/Download/patched/citizen-extended-v2-0.1316.0/
```

The directory contains `base.apk` and any required split APKs. Use APK Patcher to install them as a set; installing only the base can fail or omit required resources and native libraries.

### Background jobs and cancellation

Downloads, imports, and patching continue when the activity is closed. Use **Cancel operation** or the notification's Cancel action to stop a running job. Force-stopping the app or a hard process kill interrupts the job; it does not automatically resume, so start it again. Completed output and the selected input are restored when possible.

### Signature conflicts and updates

Patched applications are signed with APK Patcher's shared compatibility key. Android cannot update an installation that has a different signature. If installation reports a conflict, the app offers **Uninstall conflicting Citizen app**. Uninstalling removes that application's local data; back up anything important before proceeding.

Keep the same patch key for future patched updates. Replacing the key requires uninstalling the previously patched version first.

### Troubleshooting

- **No installed patch pack supports this package/version:** use Citizen `0.1316.0`, or add a compatible JSON patch pack and rebuild APK Patcher.
- **Profile is incompatible / missing methods:** the target's DEX no longer matches its pack. Update the method signatures; do not ignore missing patches.
- **Browser challenge or HTTP error:** complete the challenge in the built-in browser, retry, choose another source, or download the package manually and use **Choose a local file**.
- **Invalid or incomplete archive:** the URL may return HTML instead of an APK, or the download may be incomplete. Obtain the file again.
- **Not enough free storage:** delete unneeded downloads/output and retry. Downloads do not currently resume after interruption.
- **Installation blocked:** allow APK Patcher to install unknown apps in Android Settings and retry.
- **Signature conflict:** see the warning above. Do not uninstall until you understand the local-data consequences.
- **Operation interrupted:** reopen the app, confirm that the input is still selected, and restart the operation.

## Build and verify

Requirements: JDK 17 and an Android SDK with platform 36. Android Studio is optional.

```bat
set JAVA_HOME=C:\path\to\jdk17
set ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The installable debug build is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Project structure

- `MainActivity.kt`: Android navigation, activity results, and lifecycle integration.
- `MainView.kt`: view creation and rendering from immutable `WorkflowState` values.
- `PatchWorkflowController.kt`: UI-facing commands, installation state, and workflow observation.
- `PatchJobService.kt`: foreground download, import, and patch jobs with notifications and cancellation.
- `WorkflowStore.kt`: shared immutable state and persistence across activity/process recreation.
- `StorageSpace.kt`: free-space checks and storage estimates.
- `SourceCatalog.kt`: supported APK source names and URLs.
- `PatchDefinitions.kt`: patch-pack models, input resolution, and the built-in pack registry.
- `PatchPackParser.kt`: strict JSON parsing and schema validation.
- `MorphePackUpdater.kt`: GitHub downloads, APK indexing, and update orchestration.
- `MorphePackGenerator.kt`: Morphe fingerprint matching and safe operation translation.
- `PackRepository.kt`: downloaded-pack persistence, selection, and built-in fallback.
- `PatchPackWriter.kt`: JSON serialization and round-trip validation support.
- `res/raw/citizen_extended_v2.json`: Citizen's declarative method patches.
- `DexPatcher.kt`: DEX rewriting and method-specific register allocation.
- `ApkArchive.kt`: archive extraction, metadata, and base/split selection.
- `ApkPatcher.kt`: APK rebuild, alignment, and output pipeline.
- `ApkPackageSigner.kt`: shared-key APK signing.
- `ApkInstaller.kt`: Android `PackageInstaller` sessions and user-action handling.

Displayed strings are in `res/values/strings.xml`. Add source links in `SourceCatalog`, not in UI code.

## JSON patch packs

Patch packs declaratively describe a target application and its method replacements. They cannot execute arbitrary code. The current format version is `1`; unknown fields, operations, descriptors, input references, and duplicate method signatures are rejected.

```json
{
  "formatVersion": 1,
  "id": "example-v1",
  "name": "Example patches",
  "application": {
    "packageName": "com.example.app",
    "displayName": "Example",
    "versionName": "1.0"
  },
  "inputs": [
    {"id": "token", "type": "secretString", "required": false}
  ],
  "patches": [
    {
      "dexClass": "Lcom/example/Feature;",
      "method": "isEnabled",
      "parameters": [],
      "returnType": "Z",
      "replacement": {"type": "returnBoolean", "value": true}
    }
  ]
}
```

### Top-level values

- `formatVersion` (integer, required): schema version. It must currently be `1`.
- `id` (string, required): unique pack/profile ID, up to 80 characters. Start with a lowercase letter or number and use only lowercase letters, numbers, dots, dashes, and underscores. It is shown after patching and used in the output directory name.
- `name` (string, required): human-readable pack name.
- `application` (object, required): target compatibility information.
- `inputs` (array, required): runtime values accepted by the pack. Use `[]` when none are needed.
- `patches` (array, required): one or more method replacements. Two patches cannot have the same complete method signature.

### `application` values

- `packageName` (string, required): exact Android package name, such as `sp0n.citizen`.
- `displayName` (string, required): name shown in patch progress messages.
- `versionName` (string, optional): exact Android version to match. Omit it only when exact DEX signature validation is sufficient across versions. If package metadata has no usable version, a version-constrained pack does not match.

The first built-in pack matching both package and version is selected. If none matches, patching stops before modifying the DEX.

### `inputs` values

Each input object supports:

- `id` (string, required): reference used by `returnInput` and `requiresInputs`. Start with a letter and use letters, numbers, and underscores, up to 32 characters.
- `type` (string, required): currently only `secretString`.
- `required` (boolean, optional): defaults to `false`. If true, resolving the pack without a non-empty value fails.

Do not place actual passwords, tokens, or user IDs in the JSON. Values are supplied at runtime and resolved in memory. The current Citizen UI supplies only `userId` and `userToken`; a pack with different inputs also needs UI/input plumbing.

### `patches` values

Each patch identifies one complete DEX method signature:

- `dexClass` (string, required): DEX class descriptor, including `L` and `;`, such as `Lcom/example/Feature;`. Inner classes use `$`, for example `Lcom/example/Feature$Options;`.
- `method` (string, required): exact, case-sensitive method name. Obfuscated names such as `a` are valid.
- `parameters` (array of strings, required): exact parameter type descriptors in declaration order. Use `[]` for no parameters.
- `returnType` (string, required): exact DEX return type descriptor.
- `requiresInputs` (array of strings, optional): input IDs that must all be non-empty for this patch to be included. Omit it for unconditional patches.
- `replacement` (object, required): operation used to replace the entire method body.

Common descriptors include `Z` for boolean, `I` for integer, `J` for long, `Ljava/lang/String;` for a string, and `Lcom/example/Type;` for an object. Prefix a descriptor with `[` for an array. Parameter and return descriptors must match the target method exactly; changing only the method name is not enough.

### `replacement` values

- `{"type":"returnBoolean","value":true}` returns a constant boolean. `value` can be `true` or `false`, and `returnType` must be `Z`.
- `{"type":"returnString","value":"patched"}` returns a constant string. `returnType` must be `Ljava/lang/String;`.
- `{"type":"returnInput","inputId":"token"}` returns the runtime value of the declared input. `returnType` must be `Ljava/lang/String;`, and the same ID must appear in `requiresInputs`.
- `{"type":"returnVoid"}` immediately returns from a `V` method.
- `{"type":"returnNull"}` returns null from an object or array method.
- `{"type":"returnStaticField","classType":"Ljava/lang/Boolean;","fieldName":"TRUE","fieldType":"Ljava/lang/Boolean;"}` returns a static object field. The field type must equal the method return type, or the method may return `Ljava/lang/Object;`. This also supports `Lkotlin/Unit;->a` for coroutine collectors.
- `{"type":"dismissActivity"}` supports only `onCreate(Landroid/os/Bundle;)V`. It calls the actual superclass `onCreate`, finishes the activity, and returns.
- `{"type":"launchActivityFromField","targetClass":"Lcom/example/TargetActivity;","fieldName":"activity","fieldType":"Lcom/example/CurrentActivity;"}` reads an activity from an instance field, starts `targetClass`, finishes the current activity, and returns Kotlin `Unit`. The method must return `Ljava/lang/Object;`.

For example, a conditional credential patch contains:

```json
{
  "dexClass": "Lcom/example/Session;",
  "method": "getToken",
  "parameters": [],
  "returnType": "Ljava/lang/String;",
  "requiresInputs": ["token"],
  "replacement": {"type": "returnInput", "inputId": "token"}
}
```

Secret values are never stored in the JSON pack or workflow state. Arbitrary instructions, scripts, file changes, and network operations are not supported operations. Adding another replacement type requires parser, engine, and test changes.

To add an application or a new incompatible version, create another resource under `res/raw`, register it in `BuiltInPatchPacks`, and add parser/engine fixtures. Remote generation is currently limited to the reviewed Citizen Morphe source; arbitrary remote or user-imported packs are not enabled. The execution pipeline is application-agnostic, while the current UI, download sources, credentials, and launch actions remain Citizen-specific.

## Updating Citizen compatibility

`res/raw/citizen_extended_v2.json` contains 39 non-credential patches and three conditional credential patches for Citizen `0.1316.0`. Beyond the original gates, it covers Plus/Protect DTO and domain getters, Clarity and Safety Network flags, selected paywall activities, coroutine collectors, and Safety Network redirection. The DEX patcher requires every signature in the resolved profile to be found; it fails instead of producing a partial patch. Legacy Morphe targets absent from this Citizen version are intentionally excluded.

When Citizen changes:

1. Run the existing pack against the new APK and record all reported missing signatures.
2. Inspect those classes and determine the new class names, method names, parameter types, and return types.
3. Keep the existing pack unchanged. Add a resource with a new ID and exact `versionName` when signatures differ.
4. Extend parser and DEX tests, especially for methods with parameters or wide (`J`/`D`) parameter types.
5. Run unit tests and lint, then perform the real download/patch/install/launch check on a device.

Other replacement types require a new schema operation, `MethodReplacement` implementation, and corresponding writer/test coverage.

## Regression tests

`DexPatcherTest` generates synthetic multi-DEX APK fixtures and checks exact method matching, rewritten instructions, credential strings, duplicate definitions, missing-method diagnostics, and register counts. `PatchPackParserTest` loads the actual Citizen resource and covers generic pack parsing, input resolution, compatibility matching, and invalid schemas. `MorphePackGeneratorTest` covers exact/adjusted matching and unsupported targets; `PatchPackWriterTest` round-trips every operation.

`ApkArchiveTest` covers XAPK metadata, larger non-base splits, structural fallback, empty archives, and cancellation. `StorageSpaceTest` checks storage reservations. Synthetic fixtures avoid committing large third-party APKs. They complement rather than replace testing a real target APK.

`.github/workflows/android.yml` runs unit tests, lint, and the debug build on JDK 17 for pushes, pull requests, and manual runs. A real-target device check is still required when patch profiles change.

## Background jobs and saved state

Downloads, imports, and patching run in a foreground service with notification progress and a Cancel action. Closing or recreating the activity does not stop them. The UI restores active progress and completed output from `WorkflowStore`; a hard process kill interrupts the operation rather than automatically retrying it. Before starting, the app checks free storage using conservative working-copy estimates.

If automatic installation was selected, patching prepares the installation whenever the UI is available. If the app is closed, reopen it after completion to confirm Android's installation prompt. Installation itself must remain visible until Android finishes.

`res/raw/patch_signing_key.p12` is the intentional shared key used for all patched output. Keeping it unchanged allows patched versions to update one another. Replacing it creates a signature conflict and requires uninstalling the previously patched app. It is a compatibility key, not a secret or a production identity.

User IDs and tokens are passed only to the immediate patch operation. They are never included in persisted workflow state.

## Patch coverage attribution

The extended Citizen pack incorporates method coverage inspired by [rushiranpise/morphe-patches](https://github.com/rushiranpise/morphe-patches), particularly its Citizen Unlock Pro patch. That project is GPL-3.0 licensed. The definitions here were audited against Citizen `0.1316.0`; legacy-only fingerprints and methods absent from that version are intentionally excluded. This project is not affiliated with Morphe or the repository author.

## Current scope

The app implements the Python tool's core feature and credential patches. The Python-only manifest export, shake gate, and Medium-icon debug trigger are not ported. Source websites can change their behavior or require browser challenges; the built-in browser is therefore the fallback when direct downloads fail.
