package com.operit.fullaod;

import android.content.Context;
import android.view.ViewGroup;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;
import java.util.Set;

public class AodModule implements IXposedHookLoadPackage {

    private static final String SYSTEMUI = "com.android.systemui";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !SYSTEMUI.equals(lpparam.packageName)) return;
        if (lpparam.processName != null && !SYSTEMUI.equals(lpparam.processName)) return;

        final AodController ctrl = AodController.get();
        ctrl.log("=== FullAOD SystemUI init (v1.5-opt) ===");

        final ClassLoader cl = lpparam.classLoader;
        installShadeHook(cl, ctrl);
        installDozeHook(cl, ctrl);
        installScrimHook(cl, ctrl);
        installBrightnessHook(cl, ctrl);
    }

    private void installShadeHook(ClassLoader cl, final AodController ctrl) {
        final String[] candidates = new String[] {
                "com.android.systemui.shade.NotificationShadeWindowViewController",
                "com.android.systemui.statusbar.phone.NotificationShadeWindowViewController"
        };
        for (String name : candidates) {
            try {
                Class<?> cls = XposedHelpers.findClass(name, cl);
                if (cls == null) continue;
                XC_MethodHook attachHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        try {
                            Object mv = XposedHelpers.getObjectField(param.thisObject, "mView");
                            if (mv instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) mv;
                                ctrl.attachShadeView(vg, vg.getContext());
                            }
                        } catch (Throwable t) {
                            ctrl.log("shade hook err: " + t);
                        }
                    }
                };
                Set<XC_MethodHook.Unhook> ctors = XposedBridge.hookAllConstructors(cls, attachHook);
                int count = ctors != null ? ctors.size() : 0;
                try {
                    Method legacy = cls.getDeclaredMethod("setupExpandedStatusBar");
                    XposedBridge.hookMethod(legacy, attachHook);
                    count++;
                } catch (Throwable ignored) {
                }
                ctrl.log("hook shade vc ok: " + name + " hooks=" + count);
                return;
            } catch (Throwable ignored) {
            }
        }
        ctrl.log("hook shade vc err: class not found");
    }

    private void installDozeHook(ClassLoader cl, final AodController ctrl) {
        try {
            Class<?> dmCls = XposedHelpers.findClass("com.android.systemui.doze.DozeMachine", cl);
            if (dmCls == null) {
                ctrl.log("hook DozeMachine err: class null");
                return;
            }
            int total = 0;

            Set<XC_MethodHook.Unhook> ctors = XposedBridge.hookAllConstructors(dmCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.thisObject == null) return;
                    ctrl.setDozeMachine(param.thisObject);
                }
            });
            total += ctors != null ? ctors.size() : 0;

            XC_MethodHook stateHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.thisObject == null) return;
                    ctrl.setDozeMachine(param.thisObject);
                    Object st = null;
                    try {
                        st = XposedHelpers.getObjectField(param.thisObject, "mState");
                    } catch (Throwable ignored) {
                    }
                    if (st == null && param.args != null && param.args.length > 0) {
                        st = param.args[0];
                    }
                    if (st != null) {
                        ctrl.onDozeState(String.valueOf(st));
                    }
                }
            };

            Set<XC_MethodHook.Unhook> trHooks = XposedBridge.hookAllMethods(dmCls, "transitionTo", stateHook);
            int trCnt = trHooks != null ? trHooks.size() : 0;
            total += trCnt;

            if (trCnt == 0) {
                Set<XC_MethodHook.Unhook> rqHooks = XposedBridge.hookAllMethods(dmCls, "requestState", stateHook);
                total += rqHooks != null ? rqHooks.size() : 0;
            }

            Set<XC_MethodHook.Unhook> setHooks = XposedBridge.hookAllMethods(dmCls, "setState", stateHook);
            total += setHooks != null ? setHooks.size() : 0;

            ctrl.log("hook DozeMachine ok: hooks=" + total);
        } catch (Throwable t) {
            ctrl.log("hook DozeMachine err: " + t);
        }
    }

    private void installScrimHook(ClassLoader cl, final AodController ctrl) {
        try {
            Class<?> scCls = XposedHelpers.findClass("com.android.systemui.statusbar.phone.ScrimController", cl);
            if (scCls == null) {
                ctrl.log("hook ScrimController err: class null");
                return;
            }
            XC_MethodHook scrimHook = new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.thisObject == null) return;
                    try {
                        ctrl.onScrimChanged(param.thisObject);
                    } catch (Throwable t) {
                        ctrl.log("scrim cb err: " + t);
                    }
                }
            };
            int count = 0;
            final String[] methods = new String[] {
                    "applyAndDispatchState",
                    "applyState$1$1",
                    "updateScrims",
                    "setScrimAlpha",
                    "applyState"
            };
            for (String m : methods) {
                try {
                    Set<XC_MethodHook.Unhook> res = XposedBridge.hookAllMethods(scCls, m, scrimHook);
                    if (res != null) count += res.size();
                } catch (Throwable ignored) {
                }
            }
            ctrl.log("hook ScrimController ok: hooks=" + count);
        } catch (Throwable t) {
            ctrl.log("hook ScrimController err: " + t);
        }
    }

    private void installBrightnessHook(ClassLoader cl, final AodController ctrl) {
        int forwarderHooks = 0;
        try {
            Class<?> fCls = XposedHelpers.findClass(
                    "com.android.systemui.doze.DozeBrightnessHostForwarder", cl);
            if (fCls != null) {
                XC_MethodHook intHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ctrl.setBrightnessForwarder(param.thisObject);
                        if (param.args == null || param.args.length == 0) return;
                        if (param.args[0] instanceof Integer) {
                            int orig = (Integer) param.args[0];
                            int nb = ctrl.onBrightnessInt(orig, false);
                            if (nb != orig) param.args[0] = nb;
                        } else if (param.args[0] instanceof Float) {
                            float orig = (Float) param.args[0];
                            float nb = ctrl.onBrightness(orig, false);
                            if (nb != orig) param.args[0] = nb;
                        }
                    }
                };
                Set<XC_MethodHook.Unhook> h1 = XposedBridge.hookAllMethods(fCls, "setDozeScreenBrightness", intHook);
                if (h1 != null) forwarderHooks += h1.size();
                Set<XC_MethodHook.Unhook> h2 = XposedBridge.hookAllMethods(fCls, "setDozeScreenBrightnessFloat", intHook);
                if (h2 != null) forwarderHooks += h2.size();
                Set<XC_MethodHook.Unhook> h3 = XposedBridge.hookAllMethods(fCls, "dispatchDozeBrightness", intHook);
                if (h3 != null) forwarderHooks += h3.size();
            }
        } catch (Throwable t) {
            ctrl.log("hook forwarder brightness err: " + t);
        }

        int dreamHooks = 0;
        try {
            Class<?> dreamCls = XposedHelpers.findClass("android.service.dreams.DreamService", cl);
            if (dreamCls != null) {
                XC_MethodHook dreamIntHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject == null || param.args == null || param.args.length == 0) return;
                        if (!param.thisObject.getClass().getName().contains("DozeService")) return;
                        ctrl.setDozeService(param.thisObject);
                        if (param.args[0] instanceof Integer) {
                            int orig = (Integer) param.args[0];
                            int nb = ctrl.onBrightnessInt(orig, true);
                            if (nb != orig) param.args[0] = nb;
                        }
                    }
                };
                XC_MethodHook dreamFloatHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject == null || param.args == null || param.args.length == 0) return;
                        if (!param.thisObject.getClass().getName().contains("DozeService")) return;
                        ctrl.setDozeService(param.thisObject);
                        if (param.args[0] instanceof Float) {
                            float orig = (Float) param.args[0];
                            float nb = ctrl.onBrightness(orig, true);
                            if (nb != orig) param.args[0] = nb;
                        }
                    }
                };
                Set<XC_MethodHook.Unhook> d1 = XposedBridge.hookAllMethods(dreamCls, "setDozeScreenBrightness", dreamIntHook);
                if (d1 != null) dreamHooks += d1.size();
                Set<XC_MethodHook.Unhook> d2 = XposedBridge.hookAllMethods(dreamCls, "setDozeScreenBrightnessFloat", dreamFloatHook);
                if (d2 != null) dreamHooks += d2.size();
            }
        } catch (Throwable t) {
            ctrl.log("hook DreamService brightness err: " + t);
        }

        ctrl.log("hook brightness ok: forwarder=" + forwarderHooks + " dream=" + dreamHooks);
    }
}
