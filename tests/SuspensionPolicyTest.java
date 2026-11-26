package dev.pagetrim.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Host tests using the package-manager and preferences fixture in test-suspension.py. */
public final class SuspensionPolicyTest {
  private static final Context context = new Context();
  private static final TargetReclaim.App target = new TargetReclaim.App();

  private interface Action { void run() throws Exception; }

  private static void rejects(Action action) throws Exception {
    try {
      action.run();
      throw new AssertionError("Expected policy rejection");
    } catch (IOException expected) {
      // A rejected request must not silently change policy.
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static boolean pending() {
    return LifecycleScopes.suspended(context).contains(target.pkg);
  }

  private static void suspend() throws Exception {
    LifecycleScopes.suspend(context, target);
  }

  private static void resume() throws Exception {
    LifecycleScopes.unsuspend(context, target.pkg);
  }

  public static void main(String[] args) throws Exception {
    PackageManager.extraOwner = true;
    rejects(SuspensionPolicyTest::suspend);
    require(Root.writes == 0 && !pending(), "An existing suspension must be preserved");
    PackageManager.extraOwner = false;

    suspend();
    require(pending() && PackageManager.marker != null, "Missing journal or system marker");
    rejects(SuspensionPolicyTest::suspend);
    require(Root.writes == 1, "Duplicate suspension reached PM");
    resume();
    require(!pending() && PackageManager.marker == null, "Original state was not restored");

    suspend();
    PackageManager.extraOwner = true;
    resume();
    require(!pending() && PackageManager.extraOwner && PackageManager.marker == null,
        "Releasing our suspension must preserve the other owner");
    PackageManager.extraOwner = false;

    suspend();
    PackageManager.marker = "another root tool";
    int before = Root.writes;
    rejects(SuspensionPolicyTest::resume);
    require(pending() && before == Root.writes, "A changed root marker must block rollback");
    PackageManager.marker = null;
    resume();
    require(!pending() && before == Root.writes, "An external restoration only clears the journal");

    suspend();
    PackageManager.uid++;
    before = Root.writes;
    resume();
    require(!pending() && before == Root.writes && PackageManager.marker != null,
        "Changing installation identity must not touch the new package");
    PackageManager.marker = null;

    suspend();
    PackageManager.absent = true;
    before = Root.writes;
    resume();
    require(!pending() && before == Root.writes, "Removed package should clear only its journal");
    PackageManager.absent = false;
    PackageManager.marker = null;

    Root.shortReceipt = true;
    rejects(SuspensionPolicyTest::suspend);
    require(pending() && PackageManager.marker != null, "Unconfirmed system write lost rollback");
    Root.shortReceipt = false;
    resume();
    require(!pending(), "Could not recover the unconfirmed write");

    Build.VERSION.SDK_INT = 29;
    before = Root.writes;
    rejects(SuspensionPolicyTest::suspend);
    require(before == Root.writes, "Unsupported Android release changed policy");

    Files.write(Paths.get(args[0]), Root.lastSuspendCommand.getBytes(StandardCharsets.UTF_8));
    System.out.println("Suspension ownership, recovery journal, and identity fixtures passed.");
  }
}
