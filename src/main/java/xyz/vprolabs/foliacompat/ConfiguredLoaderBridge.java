package xyz.vprolabs.foliacompat;

import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

// Generates PluginClassLoaderBridge, a subclass of PluginClassLoader that also implements
// io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader. Paper's
// JavaPlugin() constructor refuses to run unless the plugin's defining loader implements
// that interface ("JavaPlugin requires to be created by a valid classloader"); the bridge
// makes the real constructor execute, which fixes every null-field cascade the previous
// Unsafe.allocateInstance() fallback produced (LuckPerms, FAWE, Essentials).
//
// The interface is probed at runtime and never appears on our compile classpath: the
// bridge class is emitted with reflective ASM and defined via MethodHandles.Lookup in
// this same package (so package-private access to PluginClassLoader works), exactly like
// SchedulerBridge does for CraftScheduler. Any interface method added in future Paper
// versions that PluginClassLoader does not declare gets a default-value stub, so the
// bridge class keeps loading instead of failing verification.
public final class ConfiguredLoaderBridge {

    private static final String INTERFACE_NAME = "io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader";
    private static final String GROUP_IFACE_NAME = "io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup";
    private static final String ACCESS_IFACE_NAME = "io.papermc.paper.plugin.provider.classloader.ClassLoaderAccess";
    private static final String BRIDGE_CLASS_NAME = "xyz.vprolabs.foliacompat.PluginClassLoaderBridge";
    private static final String SUPER_CLASS_NAME = "xyz/vprolabs/foliacompat/PluginClassLoader";

    private static volatile Class<?> interfaceClass;
    private static volatile boolean interfaceProbed;
    private static volatile Class<?> bridgeClass;

    private ConfiguredLoaderBridge() {}

    static boolean isAvailable() {
        Class<?> c = interfaceClass;
        if (c == null && !interfaceProbed) {
            synchronized (ConfiguredLoaderBridge.class) {
                if (interfaceClass == null && !interfaceProbed) {
                    interfaceProbed = true;
                    try {
                        interfaceClass = Class.forName(INTERFACE_NAME, false, ConfiguredLoaderBridge.class.getClassLoader());
                    } catch (Throwable t) {
                        DebugUtil.info("FC BRIDGE probe failed: " + t.getClass().getSimpleName());
                    }
                }
            }
        }
        return interfaceClass != null;
    }

    static PluginClassLoader createBridge(URL[] urls, ClassLoader parent, PluginDescriptionFile desc,
                                          File dataFolder, File jarFile, String safeName, String jarName) throws Exception {
        Class<?> bridge = getBridgeClass();
        Constructor<?> ctor = bridge.getConstructor(URL[].class, ClassLoader.class,
                PluginDescriptionFile.class, File.class, File.class, String.class, String.class);
        return (PluginClassLoader) ctor.newInstance(urls, parent, desc, dataFolder, jarFile, safeName, jarName);
    }

    @SuppressWarnings("VolatileCompoundDetector")
    private static Class<?> getBridgeClass() throws Exception {
        Class<?> c = bridgeClass;
        if (c == null) {
            synchronized (ConfiguredLoaderBridge.class) {
                if (bridgeClass == null) {
                    bridgeClass = generateAndDefine();
                }
            }
        }
        return bridgeClass;
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static Class<?> generateAndDefine() throws Exception {
        Class<?> iface = interfaceClass;
        if (iface == null) throw new IllegalStateException("ConfiguredPluginClassLoader unavailable");
        String ifaceName = iface.getName().replace('.', '/');
        String bridgeName = BRIDGE_CLASS_NAME.replace('.', '/');
        Constructor<?> superCtor = PluginClassLoader.class.getDeclaredConstructor(
                URL[].class, ClassLoader.class, PluginDescriptionFile.class, File.class, File.class,
                String.class, String.class);
        String ctorDesc = descriptor(superCtor);

        Class<?> cwClass = Class.forName("org.objectweb.asm.ClassWriter");
        Class<?> opcClass = Class.forName("org.objectweb.asm.Opcodes");

        int ACC_PUBLIC = opcClass.getField("ACC_PUBLIC").getInt(null);
        int ACC_SUPER = opcClass.getField("ACC_SUPER").getInt(null);
        int ALOAD = opcClass.getField("ALOAD").getInt(null);
        int ILOAD = opcClass.getField("ILOAD").getInt(null);
        int LLOAD = opcClass.getField("LLOAD").getInt(null);
        int FLOAD = opcClass.getField("FLOAD").getInt(null);
        int DLOAD = opcClass.getField("DLOAD").getInt(null);
        int RETURN = opcClass.getField("RETURN").getInt(null);
        int IRETURN = opcClass.getField("IRETURN").getInt(null);
        int LRETURN = opcClass.getField("LRETURN").getInt(null);
        int FRETURN = opcClass.getField("FRETURN").getInt(null);
        int DRETURN = opcClass.getField("DRETURN").getInt(null);
        int ARETURN = opcClass.getField("ARETURN").getInt(null);
        int ICONST_0 = opcClass.getField("ICONST_0").getInt(null);
        int LCONST_0 = opcClass.getField("LCONST_0").getInt(null);
        int FCONST_0 = opcClass.getField("FCONST_0").getInt(null);
        int DCONST_0 = opcClass.getField("DCONST_0").getInt(null);
        int ACONST_NULL = opcClass.getField("ACONST_NULL").getInt(null);
        int INVOKESPECIAL = opcClass.getField("INVOKESPECIAL").getInt(null);
        int INVOKEVIRTUAL = opcClass.getField("INVOKEVIRTUAL").getInt(null);
        int CHECKCAST = opcClass.getField("CHECKCAST").getInt(null);
        int computeMaxs;
        try {
            computeMaxs = opcClass.getField("COMPUTE_MAXS").getInt(null);
        } catch (NoSuchFieldException e) {
            computeMaxs = cwClass.getField("COMPUTE_MAXS").getInt(null);
        }
        int classVersion;
        try { classVersion = opcClass.getField("V17").getInt(null); }
        catch (NoSuchFieldException e) { classVersion = 61; }

        Constructor<?> cwCtor = cwClass.getConstructor(Integer.TYPE);
        Object cw = cwCtor.newInstance(computeMaxs);
        cwClass.getMethod("visit", Integer.TYPE, Integer.TYPE, String.class, String.class, String.class, String[].class)
            .invoke(cw, classVersion, ACC_PUBLIC | ACC_SUPER, bridgeName, null, SUPER_CLASS_NAME, new String[]{ifaceName});

        // Constructor: public PluginClassLoaderBridge(URL[], ClassLoader, PluginDescriptionFile, File, File, String, String)
        Object mv = cwClass.getMethod("visitMethod", Integer.TYPE, String.class, String.class, String.class, String[].class)
            .invoke(cw, ACC_PUBLIC, "<init>", ctorDesc, null, null);
        Class<?> mvClass = mv.getClass();
        invoke(mvClass, mv, "visitCode");
        invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, 0});
        int varIdx = 1;
        for (Class<?> pt : superCtor.getParameterTypes()) {
            invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, varIdx});
            varIdx += (pt == long.class || pt == double.class) ? 2 : 1;
        }
        invoke(mvClass, mv, "visitMethodInsn", new Class<?>[]{Integer.TYPE, String.class, String.class, String.class, Boolean.TYPE},
            new Object[]{INVOKESPECIAL, SUPER_CLASS_NAME, "<init>", ctorDesc, false});
        invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{RETURN});
        invoke(mvClass, mv, "visitMaxs", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{0, 0});
        invoke(mvClass, mv, "visitEnd");

        // Interface methods: forward to the PluginClassLoader method of the same name when
        // it exists (casting the return value to the interface's declared type), else emit
        // a default-value stub. Branch-free bodies, so COMPUTE_MAXS is enough (no frames).
        List<Method> ifaceMethods = collectMethods(iface);
        Method visitMeth = cwClass.getMethod("visitMethod", Integer.TYPE, String.class, String.class, String.class, String[].class);
        for (Method m : ifaceMethods) {
            Method impl = findSuperMethod(m);
            if (impl != null) {
                emitForward(cw, visitMeth, opcClass, bridgeName, ifaceName, m, impl,
                    ALOAD, ILOAD, LLOAD, FLOAD, DLOAD, INVOKEVIRTUAL, CHECKCAST,
                    RETURN, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, ACC_PUBLIC);
            } else {
                emitStub(cw, visitMeth, opcClass, m,
                    RETURN, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN,
                    ICONST_0, LCONST_0, FCONST_0, DCONST_0, ACONST_NULL, ACC_PUBLIC);
            }
        }

        cwClass.getMethod("visitEnd").invoke(cw);
        byte[] bytes = (byte[]) cwClass.getMethod("toByteArray").invoke(cw);

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> defined = lookup.defineClass(bytes);
        DebugUtil.info("FC BRIDGE defined " + defined.getName() + " implements " + iface.getName()
            + " (" + bytes.length + " bytes, " + ifaceMethods.size() + " methods)");
        return defined;
    }

    @SuppressWarnings("ExceptionSwallowDetector")
    private static Method findSuperMethod(Method ifaceMethod) {
        try {
            return PluginClassLoader.class.getMethod(ifaceMethod.getName(), ifaceMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            // No superclass method for this interface method — the generic stub is emitted instead.
            return null;
        }
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static void emitForward(Object cw, Method visitMeth, Class<?> opcClass,
                                    String bridgeName, String ifaceName, Method m, Method impl,
                                    int ALOAD, int ILOAD, int LLOAD, int FLOAD, int DLOAD,
                                    int INVOKEVIRTUAL, int CHECKCAST,
                                    int RETURN, int IRETURN, int LRETURN, int FRETURN, int DRETURN, int ARETURN,
                                    int ACC_PUBLIC) throws Exception {
        String desc = descriptor(m);
        Object mv = visitMeth.invoke(cw, ACC_PUBLIC, m.getName(), desc, null, null);
        Class<?> mvClass = mv.getClass();
        invoke(mvClass, mv, "visitCode");
        invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, 0});
        int varIdx = 1;
        for (Class<?> pt : m.getParameterTypes()) {
            int loadOp;
            if (pt == int.class || pt == short.class || pt == byte.class || pt == char.class || pt == boolean.class)
                loadOp = ILOAD;
            else if (pt == long.class)
                loadOp = LLOAD;
            else if (pt == float.class)
                loadOp = FLOAD;
            else if (pt == double.class)
                loadOp = DLOAD;
            else
                loadOp = ALOAD;
            invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{loadOp, varIdx});
            varIdx += (pt == long.class || pt == double.class) ? 2 : 1;
        }
        invoke(mvClass, mv, "visitMethodInsn", new Class<?>[]{Integer.TYPE, String.class, String.class, String.class, Boolean.TYPE},
            new Object[]{INVOKEVIRTUAL, bridgeName, impl.getName(), descriptor(impl), false});
        Class<?> ifaceRet = m.getReturnType();
        Class<?> implRet = impl.getReturnType();
        if (implRet == void.class) {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{RETURN});
        } else if (implRet.isPrimitive()) {
            int retOp = implRet == long.class ? LRETURN : (implRet == float.class ? FRETURN
                : (implRet == double.class ? DRETURN : IRETURN));
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{retOp});
        } else {
            // Covariant/downcast return: PluginDescriptionFile -> PluginMeta (upcast),
            // Object -> PluginClassLoaderGroup (downcast). One CHECKCAST covers both.
            if (!implRet.equals(ifaceRet)) {
                invoke(mvClass, mv, "visitTypeInsn", new Class<?>[]{Integer.TYPE, String.class},
                    new Object[]{CHECKCAST, ifaceRet.getName().replace('.', '/')});
            }
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{ARETURN});
        }
        invoke(mvClass, mv, "visitMaxs", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{0, 0});
        invoke(mvClass, mv, "visitEnd");
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static void emitStub(Object cw, Method visitMeth, Class<?> opcClass, Method m,
                                 int RETURN, int IRETURN, int LRETURN, int FRETURN, int DRETURN, int ARETURN,
                                 int ICONST_0, int LCONST_0, int FCONST_0, int DCONST_0, int ACONST_NULL,
                                 int ACC_PUBLIC) throws Exception {
        Object mv = visitMeth.invoke(cw, ACC_PUBLIC, m.getName(), descriptor(m), null, null);
        Class<?> mvClass = mv.getClass();
        invoke(mvClass, mv, "visitCode");
        Class<?> ret = m.getReturnType();
        if (ret == void.class) {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{RETURN});
        } else if (ret == long.class) {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{LCONST_0});
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{LRETURN});
        } else if (ret == float.class) {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{FCONST_0});
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{FRETURN});
        } else if (ret == double.class) {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{DCONST_0});
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{DRETURN});
        } else if (ret.isPrimitive()) {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{ICONST_0});
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{IRETURN});
        } else {
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{ACONST_NULL});
            invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{ARETURN});
        }
        invoke(mvClass, mv, "visitMaxs", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{0, 0});
        invoke(mvClass, mv, "visitEnd");
    }

    /** Reflective invoke with setAccessible fallback for JDK 25+ module access. No-arg variant. */
    private static Object invoke(Class<?> clazz, Object target, String name, Class<?>... paramTypes) throws Exception {
        Method m = clazz.getMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** Reflective invoke with args and matching param types. */
    private static Object invoke(Class<?> clazz, Object target, String name,
                                  Class<?>[] paramTypes, Object[] args) throws Exception {
        Method m = clazz.getMethod(name, paramTypes);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static List<Method> collectMethods(Class<?> iface) {
        List<Method> result = new ArrayList<>();
        for (Method m : iface.getMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod)) continue;
            if (m.isDefault()) continue;
            if (m.isBridge() || m.isSynthetic()) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            result.add(m);
        }
        return result;
    }

    // ---- PluginClassLoaderGroup / ClassLoaderAccess proxies ----
    // Paper asks loaders for their group and group members for cross-plugin classes. We
    // answer with a Proxy backed by the managed-loaders registry. canAccess() always
    // returns true: permissive, and matches the behavior the bridge's own loadClass
    // already has (any managed loader may resolve for anyone).

    static Object createPluginGroup() {
        Class<?> groupIface = probe(GROUP_IFACE_NAME);
        if (groupIface == null) return null;
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassByName" -> {
                    if (args == null || args.length < 1 || !(args[0] instanceof String)) return null;
                    return FoliaPluginLoader.findClassAcrossLoaders((String) args[0]);
                }
                case "add" -> {
                    if (args != null && args.length >= 1 && args[0] instanceof PluginClassLoader cl) {
                        FoliaPluginLoader.managedLoaders.add(cl);
                    }
                    return null;
                }
                case "remove" -> {
                    if (args != null && args.length >= 1 && args[0] instanceof PluginClassLoader cl) {
                        FoliaPluginLoader.managedLoaders.remove(cl);
                    }
                    return null;
                }
                case "getAccess" -> { return createAccessProxy(); }
                default -> { return defaultValue(method.getReturnType()); }
            }
        };
        try {
            return Proxy.newProxyInstance(groupIface.getClassLoader(), new Class<?>[]{groupIface}, handler);
        } catch (Exception e) {
            DebugUtil.info("FC BRIDGE group proxy failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    @SuppressWarnings("ExceptionSwallowDetector")
    private static Object createAccessProxy() {
        Class<?> accessIface = probe(ACCESS_IFACE_NAME);
        if (accessIface == null) return null;
        InvocationHandler handler = (proxy, method, args) -> {
            if ("canAccess".equals(method.getName())) return Boolean.TRUE;
            return defaultValue(method.getReturnType());
        };
        try {
            return Proxy.newProxyInstance(accessIface.getClassLoader(), new Class<?>[]{accessIface}, handler);
        } catch (Exception e) {
            // Optional optimization only: a missing access proxy degrades to permissive behavior.
            return null;
        }
    }

    @SuppressWarnings("ExceptionSwallowDetector")
    private static Class<?> probe(String name) {
        // Absence of a Paper API class is a normal outcome (older servers): return null.
        try {
            return Class.forName(name, false, ConfiguredLoaderBridge.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return 0;
    }

    private static String descriptor(Constructor<?> c) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> pt : c.getParameterTypes()) sb.append(descriptorOf(pt));
        sb.append(')');
        sb.append('V');
        return sb.toString();
    }

    private static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> pt : m.getParameterTypes()) sb.append(descriptorOf(pt));
        sb.append(')');
        sb.append(descriptorOf(m.getReturnType()));
        return sb.toString();
    }

    private static String descriptorOf(Class<?> type) {
        if (type == void.class) return "V";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        if (type == char.class) return "C";
        return "L" + type.getName().replace('.', '/') + ";";
    }
}
