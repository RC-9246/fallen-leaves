# Offer a memory action from your Android app

Fallen Leaves discovers activities for the installed user's eligible non-system apps. The user enables a scope, chooses an owner, confirms, and opens the owner's activity. Each request targets one activity explicitly. No privileged caller, service binding, file descriptor, URI permission or network connection is required.

Declare the action, default category, and the exact scopes implemented by your activity. This example offers process-local SQLite cache release.

```xml
<activity android:name=".ReleaseMemoryActivity" android:exported="true">
    <intent-filter>
        <action android:name="dev.pagetrim.app.action.MANAGE_MEMORY_V1" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="dev.pagetrim.app.MEMORY_SCOPES_V1"
               android:value="SQLITE_CACHE" />
</activity>
```

An exported activity is accessible to other apps. Check the calling package, action, supported scope and request identifier. Verify the caller's signing certificate if its identity is part of your authorization model. The release signing certificate SHA-256 is `d8829083cc81e4666a170dad4a6dcecfe16723527c7fc7a29bf41d0429360a1f`. Ask the user before acting and validate the resources in the owning process. Return a short explanation, including a zero result when appropriate.

```java
// Inside the owner Activity's onCreate(), after super.onCreate().
Intent request = getIntent();
String scope = request.getStringExtra("scope");
String id = request.getStringExtra("request_id");
if (!"dev.pagetrim.app".equals(getCallingPackage())
        || !"dev.pagetrim.app.action.MANAGE_MEMORY_V1".equals(request.getAction())
        || !"SQLITE_CACHE".equals(scope) || id == null || id.length() > 64) {
    finish();
    return;
}
new AlertDialog.Builder(this)
        .setMessage("Release unused SQLite cache in this process?")
        .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
        .setOnCancelListener(dialog -> finish())
        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
            int bytes = SQLiteDatabase.releaseMemory();
            setResult(RESULT_OK, new Intent().putExtra("scope", scope)
                    .putExtra("request_id", id)
                    .putExtra("summary", "SQLite reported " + bytes + " bytes released."));
            finish();
        }).show();
```

Scopes are case-sensitive and separated by commas or whitespace in the metadata. Implement each scope you advertise.

| Scope | Owner responsibility |
| --- | --- |
| MANAGED_GC | Request garbage collection in the process that owns the objects. |
| NATIVE_ALLOCATOR | Ask the local allocator to return eligible free pages. |
| OBJECT_POOL, IMAGE_CACHE | Drop reconstructible objects or decoded images. |
| GPU_RESOURCES | Release idle textures or other reconstructible graphics resources under the owner's lifetime rules. |
| SQLITE_CACHE | Ask local SQLite to release memory. |
| DISCARDABLE_PRIVATE | Release only private contents the owner explicitly discarded. |
| ACTIVITY_INSTANCE, BROWSER_TAB | Confirm the page can be reconstructed and preserve necessary state. |
| WEBVIEW_RENDERER | Handle renderer exit for every affected WebView. |
| PRIVATE_WORKER_EXIT | Check dependencies and stop only an owned worker. |
| SAVE_SESSION, RESTORE_SESSION | Store and restore the owner's supported application state. |

Fallen Leaves checks that the component and certificate still match before dispatch, then verifies the returned scope, request ID and installed signer. Its history labels the returned summary as the owner's own report. An acceptance or an allocator byte count does not establish a system-wide gain in physical RAM.
