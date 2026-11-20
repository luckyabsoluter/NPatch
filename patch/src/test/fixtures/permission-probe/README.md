# Custom Permission Isolation Probe

The sample app declares a signature permission whose name uses a different prefix from the app's package name. It also checks a permission name generated from its current package name for a dynamically registered receiver.

## Build and reproduce

1. Run the Gradle wrapper from the repository root. The sample app uses the project's shared build settings and the Android debug key.

   ```sh
   ./gradlew :permission-probe:assembleDebug
   ```

   On Windows, use `gradlew.bat` with the same task. The APK is written to `patch/build/permission-probe/gradle/outputs/apk/debug/permission-probe-debug.apk`.

2. Install the sample APK and keep it installed. Patch two copies in Manager using `example.permissionprobe.copya` and `example.permissionprobe.copyb`, with **Isolate custom permissions** enabled. To run the probe with signature bypass disabled, also enable **Inject Loader DEX**. The corresponding CLI flags are `--sigbypasslv 0 --injectdex`.
3. Install both copies and open all three apps. Each should report that the literal and generated permissions were granted, the unknown permission was denied, the external permission was granted, and the protected broadcast was received.
4. Run **Update loader** on either copy. The update should install in place and the checks should still pass. Use `aapt2 dump permissions` to inspect the permission declarations in the original and patched APKs.

Run the automated manifest and runtime name-mapping tests from the repository root.

```sh
./gradlew :patch:test :patch-loader:testDebugUnitTest
```

## Permission mapping

NPatch uses declarations in the base APK to establish ownership. It derives names for those permissions and groups from a random instance ID and applies the same mapping to supported references in the base and all splits. Protection levels and external permission references are preserved. An external app that uses a permission defined by the source app may need explicit integration with the patched app.

The loader maps names for supported `Context` permission checks, protected broadcasts and receiver registration, `PackageManager` permission and group queries, and standard runtime permission requests. Android makes the grant and enforcement decisions. Permission metadata queries return the installed identity. Request callbacks translate mapped names back while preserving grant results. The AndroidX-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` name is supported when the source declares it and the generated alias does not refer to an external permission.

The original and isolated names are recorded under `permissionMapping.permissions` and `permissionMapping.groups` in the patched base APK's `assets/npatch/config.json`.

## Updates and repatching

Manager stores the instance ID privately and can recover it from the installed patched base. For an update from a newer original APK, keep the same target package name and enable permission isolation again. **Update loader** extracts the original base automatically.

The CLI stores IDs under `~/.npatch/permissions` by default. Use `--permission-state-dir` for another persistent location. To recover a mapping on a different machine, supply the original base and matching splits, the same target package name, `--isolate-permissions`, and `--previous-apk` pointing to the previous patched base APK. Updates require a signing key compatible with the installed copy.

## Compatibility limits

Permission trees and dynamically defined permissions, resource-backed manifest permission names, and permissions or groups declared only in a split require separate handling. The patcher reports these inputs when isolation is enabled. Arbitrary dynamically generated names, direct JNI or Binder calls, and other framework paths are outside the supported runtime mapping. The loader reports unavailable or failed hooks under `NPatch-PermissionMapping`.
