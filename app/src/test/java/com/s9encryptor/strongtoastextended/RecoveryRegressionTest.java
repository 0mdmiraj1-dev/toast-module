package com.s9encryptor.strongtoastextended;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.FrameLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, shadows={RecoveryRegressionTest.Access.class, RecoveryRegressionTest.Logs.class}, instrumentedPackages={"de.robv.android.xposed"})
@LooperMode(LooperMode.Mode.PAUSED)
public class RecoveryRegressionTest {
    FakeControl control;
    @Before public void setup() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get();
        control=new FakeControl(new FakeToast(activity)); activity.setContentView(control.mMiuiStrongToastCallBack);
        set("main",new Handler(Looper.getMainLooper())); set("toastControl",control);
        Bundle saved=new Bundle(); saved.putString("s9_mode","styled");saved.putString("s9_style","bold");
        saved.putString("param","{\"left\":{\"textParams\":{\"text\":\"S9_Encryptor\"}}}");
        saved.putString("package_name","com.sevtinge.hyperceiler"); saved.putLong("s9_countdown_ms",60000);
        saved.putBoolean("s9_auto_restore",true); set("saved",saved);
        set("expiresAt",SystemClock.elapsedRealtime()+60000L); set("countdownEnd",SystemClock.elapsedRealtime()+30000L);
        set("countdownDuration",30000L);set("generation",11);set("replayFailures",0);
        set("shadeExpanded",false);set("ccExpanded",false);set("nativeDismissed",true);
        set("pendingRestore",null);set("finalExpiry",null);set("replaying",false);
    }
    @After public void cleanup() throws Exception { call("clearCache",new Class[]{String.class},"test cleanup"); }
    @Test public void panelHeldForTwentySecondsStillRecoversOnlyRemainingDuration() throws Exception {
        control.mControlPanelWindowManager.isExpand=true;
        long deadline=(Long)get("expiresAt");
        call("restoreIfNeeded",new Class[]{int.class},11);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(0,control.starts);
        control.mControlPanelWindowManager.isExpand=false;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        assertEquals(1,control.starts);
        assertEquals(deadline-SystemClock.elapsedRealtime(),control.last.getLong("duration"),1500);
        assertEquals("bold",control.last.getString("s9_style"));
        assertEquals("com.sevtinge.hyperceiler",control.last.getString("package_name"));
        assertEquals(60000L,control.last.getLong("s9_countdown_ms"));
        assertEquals(deadline,get("expiresAt"));
    }
    @Test public void iconDismissalWaitsFiveSecondsWithoutANewRootProcess() throws Exception {
        call("markHiddenIfCurrent",new Class[]{Object.class,String.class},control.mMiuiStrongToastCallBack,"icon");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4900));assertEquals(0,control.starts);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertEquals(1,control.starts);
    }
    @Test public void visibleViewAndStaleGenerationNeverDuplicateToast() throws Exception {
        control.mMiuiStrongToastCallBack.mStrongToastShow=true;
        call("restoreIfNeeded",new Class[]{int.class},11);assertEquals(0,control.starts);
        control.mMiuiStrongToastCallBack.mStrongToastShow=false;
        call("restoreIfNeeded",new Class[]{int.class},10);assertEquals(0,control.starts);
    }
    @Test public void explicitStopCancelsPendingRestoreAndExpiredStatusNeverRestarts() throws Exception {
        call("scheduleRestore",new Class[]{long.class,String.class},5000L,"test");
        StrongToastHook.stopFromBridge(control);assertNull(get("saved"));assertEquals(1,control.hides);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertEquals(0,control.starts);
    }
    @Test public void nativeFailuresHaveAFiniteRetryBudget() throws Exception {
        control.fail=true; call("restoreIfNeeded",new Class[]{int.class},11);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45));
        assertEquals(5,control.starts);assertNull(get("pendingRestore"));
    }
    @Test public void countdownAlertAndCleanupRunEvenWhileVisualFramesArePaused() throws Exception {
        set("countdownEnd",SystemClock.elapsedRealtime()+1000L);
        StrongToastEffects.start("timer","normal",1000L,control,new Handler(Looper.getMainLooper()),SystemClock.elapsedRealtime()+31000L);
        StrongToastEffects.pause();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        Field f=StrongToastEffects.class.getDeclaredField("session");f.setAccessible(true);Object session=f.get(null);
        assertNotNull(session);Field alert=session.getClass().getDeclaredField("alertStarted");alert.setAccessible(true);assertTrue(alert.getBoolean(session));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));assertNull(f.get(null));
    }
    @Test public void screenWakeReconcilesPausedTimerAcrossDeepSleep() throws Exception {
        long now=SystemClock.elapsedRealtime();
        set("countdownEnd",now+1000L);set("expiresAt",now+31000L);
        StrongToastEffects.start("timer","normal",1000L,control,new Handler(Looper.getMainLooper()),now+31000L);
        StrongToastEffects.pause();
        long uptime=SystemClock.uptimeMillis();
        org.robolectric.shadows.ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(5));
        assertEquals(uptime,SystemClock.uptimeMillis());
        StrongToastEffects.onScreenWake();
        Field f=StrongToastEffects.class.getDeclaredField("session");f.setAccessible(true);Object session=f.get(null);
        assertNotNull(session);Field alert=session.getClass().getDeclaredField("alertStarted");alert.setAccessible(true);
        assertTrue(alert.getBoolean(session));
        org.robolectric.shadows.ShadowSystemClock.simulateDeepSleep(Duration.ofSeconds(30));
        StrongToastEffects.onScreenWake();assertNull(f.get(null));assertNull(get("saved"));
    }
    private static void set(String key,Object value)throws Exception{Field f=StrongToastHook.class.getDeclaredField(key);f.setAccessible(true);f.set(null,value);}
    private static Object get(String key)throws Exception{Field f=StrongToastHook.class.getDeclaredField(key);f.setAccessible(true);return f.get(null);}
    private static Object call(String key,Class<?>[] types,Object...args)throws Exception{Method m=StrongToastHook.class.getDeclaredMethod(key,types);m.setAccessible(true);return m.invoke(null,args);}
    public static class Panel { public boolean isExpand=false; }
    public static class Central { public boolean mPanelExpanded=false; }
    public static class Keyguard { public boolean mShowing=false; }
    public static class FakeToast extends FrameLayout {
        public boolean mStrongToastShow=false;
        public TextView mLeftTextView;
        FakeToast(Context c){super(c);mLeftTextView=new TextView(c);mLeftTextView.setText("Remaining 00:01");addView(mLeftTextView);}
    }
    public static class FakeControl {
        public FakeToast mMiuiStrongToastCallBack;public Panel mControlPanelWindowManager=new Panel();public Central mCentralSurfaces=new Central();public Keyguard mKeyguardStateController=new Keyguard();
        int starts,hides;boolean fail;Bundle last;
        FakeControl(FakeToast t){mMiuiStrongToastCallBack=t;}
        public void showCustomStrongToast(Bundle b){starts++;if(fail)throw new IllegalStateException("native race");last=new Bundle(b);mMiuiStrongToastCallBack.mStrongToastShow=true;}
        public void setStatus(int value,String type,Bundle b){hides++;mMiuiStrongToastCallBack.mStrongToastShow=false;}
    }
    @Implements(value=XposedHelpers.class,isInAndroidSdk=false)
    public static class Access {
        @Implementation protected static Object getObjectField(Object value,String key){try{for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass()){try{Field f=c.getDeclaredField(key);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException ignored){}}throw new IllegalArgumentException(key);}catch(Exception e){throw new RuntimeException(e);}}
        @Implementation protected static boolean getBooleanField(Object value,String key){return (Boolean)getObjectField(value,key);}
        @Implementation protected static float getFloatField(Object value,String key){return ((Number)getObjectField(value,key)).floatValue();}
        @Implementation protected static Object callMethod(Object value,String key,Object...args){try{for(Method m:value.getClass().getDeclaredMethods())if(m.getName().equals(key)&&m.getParameterCount()==args.length){m.setAccessible(true);try{return m.invoke(value,args);}catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}}throw new IllegalArgumentException(key);}catch(Exception e){throw new RuntimeException(e);}}
    }
    @Implements(value=XposedBridge.class,isInAndroidSdk=false)
    public static class Logs { @Implementation protected static void log(String message){} @Implementation protected static void log(Throwable error){} }
}
