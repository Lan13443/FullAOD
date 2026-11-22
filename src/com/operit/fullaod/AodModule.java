package com.operit.fullaod;

import android.app.Application;
import android.view.ViewGroup;

import de.robv.android.xposed.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class AodModule implements IXposedHookLoadPackage {

    private static final String SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!SYSTEMUI.equals(lp.packageName)) return;
        try {
            XposedBridge.log("FullAOD: loading into " + lp.packageName + " process=" + lp.processName);
            AodController c = AodController.get();
            try {
                Application app = AndroidAppHelper.currentApplication();
                if (app != null) c.setContext(app);
            } catch (Throwable ignored) {
            }
            installShadeHook(lp.classLoader, c);
            installDozeHook(lp.classLoader, c);
            installScrimHook(lp.classLoader, c);
            installBrightnessHook(lp.classLoader, c);
            c.log("all hooks installed");
        } catch (Throwable t) {
            XposedBridge.log("FullAOD: fatal " + t);
        }
    }

    private void installShadeHook(ClassLoader cl, final AodController c) {
        try {
            Class<?> vc = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowViewController", cl);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object root = null;
                        String[] names = {"mView", "mNotificationShadeWindowView", "mRootView"};
                        for (String n : names) {
                            try {
                                java.lang.reflect.Field f =
                                        XposedHelpers.findField(p.thisObject.getClass(), n);
                                if (f != null) {
                                    f.setAccessible(true);
                                    root = f.get(p.thisObject);
                                }
                            } catch (Throwable ignored) {
                            }
                            if (root instanceof ViewGroup) break;
                        }
                        if (root instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) root;
                            c.attachShadeView(vg, vg.getContext());
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("FullAOD: shade attach fail " + t);
                    }
                }
            };
            XposedBridge.hookAllMethods(vc, "setupExpandedStatusBar", h);
            XposedBridge.hookAllConstructors(vc, h);
            c.log("hook shade vc ok");
        } catch (Throwable t) {
            XposedBridge.log("FullAOD: shade hook fail " + t);
        }
    }

    private void installDozeHook(ClassLoader cl, final AodController c) {
        try {
            Class<?> dm = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine", cl);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (p.thisObject != null) c.setDozeMachine(p.thisObject);
                        if (p.args != null && p.args.length > 0 && p.args[0] != null) {
                            c.onDozeState(String.valueOf(p.args[0]));
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("FullAOD: doze state fail " + t);
                    }
                }
            };
            XposedBridge.hookAllMethods(dm, "transitionTo", h);
            XposedBridge.hookAllMethods(dm, "setState", h);
            c.log("hook DozeMachine ok");
        } catch (Throwable t) {
            XposedBridge.log("FullAOD: doze hook fail " + t);
        }
    }

    private void installScrimHook(ClassLoader cl, final AodController c) {
        try {
            Class<?> sc = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.ScrimController", cl);
            XC_MethodHook h = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        c.onScrimChanged(p.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log("FullAOD: scrim fail " + t);
                    }
                }
            };
            XposedBridge.hookAllMethods(sc, "applyAndDispatchState", h);
            XposedBridge.hookAllMethods(sc, "updateScrims", h);
            c.log("hook ScrimController ok");
        } catch (Throwable t) {
            XposedBridge.log("FullAOD: scrim hook fail " + t);
        }
    }

    private void installBrightnessHook(ClassLoader cl, final AodController c) {
        try {
            Class<?> bh = XposedHelpers.findClass(
                    "com.android.systemui.doze.DozeBrightnessHostForwarder", cl);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length == 0) return;
                        Object a0 = p.args[0];
                        if (a0 instanceof Float) {
                            p.args[0] = c.onBrightness((Float) a0);
                        } else if (a0 instanceof Integer) {
                            p.args[0] = Integer.valueOf(c.onBrightnessInt(((Integer) a0).intValue()));
                        } else if (a0 instanceof Number) {
                            float in = ((Number) a0).floatValue();
                            float out = c.onBrightness(in);
                            p.args[0] = Float.valueOf(out);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("FullAOD: brightness fail " + t);
                    }
                }
            };
            XposedBridge.hookAllMethods(bh, "dispatchDozeBrightness", h);
            c.log("hook brightness ok");

            try {
                Class<?> ds = XposedHelpers.findClass("com.android.systemui.doze.DozeService", cl);
                XposedBridge.hookAllMethods(ds, "setDozeScreenBrightness", h);
                XposedBridge.hookAllMethods(ds, "setDozeScreenBrightnessInternal", h);
                c.log("hook DozeService brightness ok");
            } catch (Throwable t) {
                XposedBridge.log("FullAOD: doze service brightness skip " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log("FullAOD: brightness hook fail " + t);
        }
    }
}