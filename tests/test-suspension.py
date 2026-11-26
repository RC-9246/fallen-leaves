#!/usr/bin/env python3
"""Run the production suspension policy on the host with an in-memory package manager.
Requires JDK 17+, Python 3 and awk. No SDK, phone, network or third-party libraries.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
here = Path(__file__).resolve().parent
source = here.parent / "app/src/main/java/dev/pagetrim/app/LifecycleScopes.java"
stubs = {
"android.content.Context": """public class Context {
 public static final int MODE_PRIVATE=0;
 public final java.util.Map<String,SharedPreferences> prefs=new java.util.HashMap<>();
 public <T> T getSystemService(Class<T> klass) { try {return klass.newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public SharedPreferences getSharedPreferences(String n,int m){return prefs.computeIfAbsent(n,x->new SharedPreferences());}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
 public Object getContentResolver(){return this;}
}""",
"android.content.SharedPreferences": """public class SharedPreferences {
 java.util.Map<String,String> record=new java.util.HashMap<>();
 public boolean contains(String s){return record.containsKey(s);} public java.util.Map<String,String>getAll(){return record;}
 public String getString(String s,String def){return record.getOrDefault(s,def);} public Editor edit(){return new Editor();}
 public class Editor { public Editor remove(String s){record.remove(s);return this;} public Editor putString(String s,String v){record.put(s,v);return this;}public boolean commit(){return true;} }
}""",
"android.content.ComponentName":"public class ComponentName {public static ComponentName unflattenFromString(String s){return null;}public String getPackageName(){return null;} }",
"android.content.Intent":'public class Intent {public static String ACTION_MAIN="",CATEGORY_HOME="";public Intent(String s){}public Intent addCategory(String s){return this;}}',
"android.content.pm.PackageManager":"""public class PackageManager {
 public static int MATCH_DEFAULT_ONLY=1,uid=10001; public static boolean extraOwner,absent;
 public static String marker; public boolean isPackageSuspended(String p){return marker!=null || extraOwner;}
 public PackageInfo getPackageInfo(String p,int f)throws NameNotFoundException {if(absent)throw new NameNotFoundException();return new PackageInfo();}
 public static class NameNotFoundException extends Exception{} public ResolveInfo resolveActivity(android.content.Intent i,int f){return null;}
}""",
"android.content.pm.PackageInfo":"public class PackageInfo { public long firstInstallTime=1;public Info applicationInfo=new Info();public static class Info {public int uid=PackageManager.uid;} }",
"android.content.pm.ResolveInfo":"public class ResolveInfo {public AI activityInfo; public static class AI {public String packageName;}}",
"android.content.pm.LauncherApps":"""public class LauncherApps {public android.os.Bundle getSuspendedPackageLauncherExtras(String p,Object user){return PackageManager.marker==null?null:new android.os.Bundle();}}""",
"android.app.admin.DevicePolicyManager":"public class DevicePolicyManager {public java.util.List<android.content.ComponentName>getActiveAdmins(){return null;}}",
"android.os.Build":"public class Build {public static class VERSION {public static int SDK_INT=30;}}",
"android.os.Bundle":"public class Bundle {public String getString(String key){return android.content.pm.PackageManager.marker;}}",
"android.os.Process":"public class Process {public static int myUid(){return 10002;}public static Object myUserHandle(){return null;}}",
"android.provider.Settings":'public class Settings {public static class Secure {public static String DEFAULT_INPUT_METHOD="",ENABLED_ACCESSIBILITY_SERVICES="";public static String getString(Object r,String k){return null;}}}',
"org.json.JSONObject":"""public class JSONObject { static int seq; static java.util.Map<String,JSONObject> all=new java.util.HashMap<>();
 java.util.Map<String,Object> values=new java.util.HashMap<>();public JSONObject(){}public JSONObject(String s){values=all.get(s).values;}
 public JSONObject put(String k,Object v){values.put(k,v);return this;} public String getString(String s){return (String)values.get(s);} public int getInt(String s){return (int)values.get(s);}
 public String toString(){String s="JSON_"+(++seq);all.put(s,this);return s;}
}""",
"dev.pagetrim.app.Cooperative":'class Cooperative { static String signer(android.content.Context c,String p){return "signer";} }',
"dev.pagetrim.app.TargetReclaim":"""class TargetReclaim {static class App {String pkg="dev.fallen.fixture";int processCount(){return 1;}} static void validate(android.content.Context c,App a){}static String validatedPrelude(App a){return "";} }""",
"dev.pagetrim.app.Root":"""class Root {
 static int writes;static boolean shortReceipt; static String lastSuspendCommand;
 static String run(String c,int timeout){if(c.contains("cmd package suspend --les ")){
  String[] args=c.substring(c.indexOf("cmd package suspend --les")).split(" ");
  if(!args[4].equals("dev.pagetrim.app.suspension_id") || !args[5].matches("[0-9a-f-]{36}") || !args[6].equals("--user") || !args[7].equals("0"))throw new AssertionError(c);
  lastSuspendCommand=c;writes++;android.content.pm.PackageManager.marker=args[5];}
 else if(c.equals("cmd package unsuspend --user 0 dev.fallen.fixture")){writes++; android.content.pm.PackageManager.marker=null;}
 else throw new AssertionError(c);
 return shortReceipt?"": "Package dev.fallen.fixture new suspended state: "+new android.content.pm.PackageManager().isPackageSuspended("dev.fallen.fixture");}
}""",
}

with TemporaryDirectory() as dirname:
    tmp = Path(dirname)
    paths = []
    for name, body in stubs.items():
        path = tmp.joinpath(*name.split(".")).with_suffix(".java")
        path.parent.mkdir(exist_ok=True, parents=True)
        path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + body)
        paths.append(path)
    test = here / "SuspensionPolicyTest.java"
    command_path = tmp / "command.txt"
    subprocess.run(["javac", "--release", "8", "-encoding", "UTF-8", "-d", tmp,
                    source, test, *paths], check=True)
    subprocess.run(["java", "-cp", tmp, "dev.pagetrim.app.SuspensionPolicyTest", command_path],
                   check=True)

    # Domain-verification records repeat User 0: in real dumps. Match exactly
    # one boolean package-state field, and reject ambiguous or cross-user dumps.
    program = command_path.read_text().split(" | awk '", 1)[1].split("' ||", 1)[0]
    state_fixtures = [
        ("User 0: verified links\nUser 0: installed=true suspended=false\nUser 0: links", True),
        ("User 0: installed=true suspended=true\nUser 0: other", False),
        ("User 0: suspended=false\nUser 0: suspended=false", False),
        ("User 1: suspended=false", False),
    ]
    for dump, expected in state_fixtures:
        result = subprocess.run(["awk", program], input=dump, text=True)
        assert (result.returncode == 0) == expected, dump
    print("Per-user package dump parser fixtures passed.")
