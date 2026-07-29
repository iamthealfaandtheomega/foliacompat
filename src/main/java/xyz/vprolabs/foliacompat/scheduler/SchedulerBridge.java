package xyz.vprolabs.foliacompat.scheduler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.scheduler.BukkitScheduler;
import xyz.vprolabs.foliacompat.FoliaPluginLoader;
import xyz.vprolabs.foliacompat.DebugUtil;

public class SchedulerBridge {

    private static volatile Class<?> bridgeClass;

    @SuppressWarnings("VolatileCompoundDetector")
    public static Object createBridge(FoliaBukkitScheduler delegate, Class<?> fieldType) {
        if (bridgeClass == null) {
            synchronized (SchedulerBridge.class) {
                if (bridgeClass == null) {
                    bridgeClass = generateAndDefine(fieldType, BukkitScheduler.class);
                }
            }
        }
        try {
            Constructor<?> ctor = bridgeClass.getConstructor(Object.class);
            return ctor.newInstance(delegate);
        } catch (Exception e) {
            throw new RuntimeException("FC BRIDGE instantiation failed", e);
        }
    }

    /** Reflectively load ASM from server classpath and generate the bridge. */
    @SuppressWarnings("JavaReflectionInvocation")
    private static Class<?> generateAndDefine(Class<?> superClass, Class<?> iface) {
        String superName = internalName(superClass);
        String ifaceName  = internalName(iface);
        String bridgeName = "xyz/vprolabs/foliacompat/scheduler/Bridge_" + superClass.getSimpleName();

        byte[] bytes;
        try {
            Class<?> cwClass     = Class.forName("org.objectweb.asm.ClassWriter");
            Class<?> opcClass    = Class.forName("org.objectweb.asm.Opcodes");

            int ACC_PUBLIC   = opcClass.getField("ACC_PUBLIC").getInt(null);
            int ACC_SUPER    = opcClass.getField("ACC_SUPER").getInt(null);
            int ACC_PRIVATE  = opcClass.getField("ACC_PRIVATE").getInt(null);
            int ACC_FINAL    = opcClass.getField("ACC_FINAL").getInt(null);
            int ALOAD   = opcClass.getField("ALOAD").getInt(null);
            int ILOAD   = opcClass.getField("ILOAD").getInt(null);
            int LLOAD   = opcClass.getField("LLOAD").getInt(null);
            int FLOAD   = opcClass.getField("FLOAD").getInt(null);
            int DLOAD   = opcClass.getField("DLOAD").getInt(null);
            int RETURN  = opcClass.getField("RETURN").getInt(null);
            int IRETURN = opcClass.getField("IRETURN").getInt(null);
            int LRETURN = opcClass.getField("LRETURN").getInt(null);
            int FRETURN = opcClass.getField("FRETURN").getInt(null);
            int DRETURN = opcClass.getField("DRETURN").getInt(null);
            int ARETURN = opcClass.getField("ARETURN").getInt(null);
            int INVOKESPECIAL   = opcClass.getField("INVOKESPECIAL").getInt(null);
            int INVOKEINTERFACE = opcClass.getField("INVOKEINTERFACE").getInt(null);
            int PUTFIELD = opcClass.getField("PUTFIELD").getInt(null);
            int GETFIELD = opcClass.getField("GETFIELD").getInt(null);
            int computeMaxs;
            try {
                computeMaxs = opcClass.getField("COMPUTE_MAXS").getInt(null);
            } catch (NoSuchFieldException e) {
                computeMaxs = cwClass.getField("COMPUTE_MAXS").getInt(null);
            }

            Constructor<?> cwCtor = cwClass.getConstructor(Integer.TYPE);
            Object cw = cwCtor.newInstance(computeMaxs);

            // visit(version, access, name, signature, superName, interfaces)
            int classVersion;
            try { classVersion = opcClass.getField("V17").getInt(null); }
            catch (NoSuchFieldException e) { classVersion = 61; }
            cwClass.getMethod("visit", Integer.TYPE, Integer.TYPE, String.class, String.class, String.class, String[].class)
                .invoke(cw, classVersion, ACC_PUBLIC | ACC_SUPER, bridgeName, null, superName, null);

            // field: private final Object delegate
            cwClass.getMethod("visitField", Integer.TYPE, String.class, String.class, String.class, Object.class)
                .invoke(cw, ACC_PRIVATE | ACC_FINAL, "delegate", "Ljava/lang/Object;", null, null);

            // constructor: public Bridge(Object delegate) { super(); this.delegate = delegate; }
            addCtor(cw, cwClass, opcClass, bridgeName, superName, ALOAD, INVOKESPECIAL, PUTFIELD, RETURN, ACC_PUBLIC);

            // Generate one delegate method per BukkitScheduler interface method
            List<Method> methods = collectMethods(iface);
            Method visitMeth = cwClass.getMethod("visitMethod", Integer.TYPE, String.class, String.class, String.class, String[].class);
            for (Method m : methods) {
                addDelegateMethod(cw, visitMeth, opcClass, bridgeName, ifaceName, m,
                    ALOAD, ILOAD, LLOAD, FLOAD, DLOAD,
                    GETFIELD, INVOKEINTERFACE,
                    RETURN, IRETURN, LRETURN, FRETURN, DRETURN, ARETURN, ACC_PUBLIC);
            }

            cwClass.getMethod("visitEnd").invoke(cw);
            bytes = (byte[]) cwClass.getMethod("toByteArray").invoke(cw);
            DebugUtil.info("FC BRIDGE ASM OK: " + bytes.length + " bytes, " + methods.size() + " methods");
        } catch (Exception e) {
            throw new RuntimeException("FC BRIDGE ASM fail: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        // Define the class via MethodHandles.Lookup.defineClass
        try {
            java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
            Class<?> cl = lookup.defineClass(bytes);
            DebugUtil.info("FC BRIDGE defined " + cl.getName() + " extends " + cl.getSuperclass().getSimpleName());
            return cl;
        } catch (Exception e) {
            throw new RuntimeException("FC BRIDGE define fail: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static void addCtor(Object cw, Class<?> cwClass, Class<?> opcClass,
                                String bridgeName, String superName,
                                int ALOAD, int INVOKESPECIAL, int PUTFIELD, int RETURN,
                                int ACC_PUBLIC) throws Exception {
        Object mv = cwClass.getMethod("visitMethod", Integer.TYPE, String.class, String.class, String.class, String[].class)
            .invoke(cw, ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        Class<?> mvClass = mv.getClass();
        invoke(mvClass, mv, "visitCode");
        invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, 0});
        invoke(mvClass, mv, "visitMethodInsn", new Class<?>[]{Integer.TYPE, String.class, String.class, String.class, Boolean.TYPE},
            new Object[]{INVOKESPECIAL, superName, "<init>", "()V", false});
        invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, 0});
        invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, 1});
        invoke(mvClass, mv, "visitFieldInsn", new Class<?>[]{Integer.TYPE, String.class, String.class, String.class},
            new Object[]{PUTFIELD, bridgeName, "delegate", "Ljava/lang/Object;"});
        invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{RETURN});
        invoke(mvClass, mv, "visitMaxs", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{2, 2});
        invoke(mvClass, mv, "visitEnd");
    }

    @SuppressWarnings("JavaReflectionInvocation")
    private static void addDelegateMethod(Object cw, Method visitMeth, Class<?> opcClass,
                                          String bridgeName, String ifaceName, Method m,
                                          int ALOAD, int ILOAD, int LLOAD, int FLOAD, int DLOAD,
                                          int GETFIELD, int INVOKEINTERFACE,
                                          int RETURN, int IRETURN, int LRETURN, int FRETURN, int DRETURN, int ARETURN,
                                          int ACC_PUBLIC) throws Exception {
        String mn = m.getName();
        String md = descriptor(m);
        Class<?>[] paramTypes = m.getParameterTypes();
        Class<?> retType = m.getReturnType();

        Object mv = visitMeth.invoke(cw, ACC_PUBLIC, mn, md, null, null);
        Class<?> mvClass = mv.getClass();
        invoke(mvClass, mv, "visitCode");

        invoke(mvClass, mv, "visitVarInsn", new Class<?>[]{Integer.TYPE, Integer.TYPE}, new Object[]{ALOAD, 0});
        invoke(mvClass, mv, "visitFieldInsn", new Class<?>[]{Integer.TYPE, String.class, String.class, String.class},
            new Object[]{GETFIELD, bridgeName, "delegate", "Ljava/lang/Object;"});

        int varIdx = 1;
        for (Class<?> pt : paramTypes) {
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
            new Object[]{INVOKEINTERFACE, ifaceName, mn, md, true});

        int retOp = RETURN;
        if (retType == long.class) retOp = LRETURN;
        else if (retType == float.class) retOp = FRETURN;
        else if (retType == double.class) retOp = DRETURN;
        else if (retType == void.class) retOp = RETURN;
        else if (retType.isPrimitive()) retOp = IRETURN;
        else retOp = ARETURN;
        invoke(mvClass, mv, "visitInsn", new Class<?>[]{Integer.TYPE}, new Object[]{retOp});

        invoke(mvClass, mv, "visitMaxs", new Class<?>[]{Integer.TYPE, Integer.TYPE},
            new Object[]{1 + paramTypes.length, 1 + paramTypes.length});
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

    // ---- helpers ----

    private static List<Method> collectMethods(Class<?> iface) {
        List<Method> result = new ArrayList<>();
        for (Method m : iface.getMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
            if (m.isBridge() || m.isSynthetic()) continue;
            String n = m.getName();
            if (n.equals("equals") || n.equals("hashCode") || n.equals("toString")) continue;
            result.add(m);
        }
        return result;
    }

    private static String internalName(Class<?> cl) {
        return cl.getName().replace('.', '/');
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
        return "L" + internalName(type) + ";";
    }
}
