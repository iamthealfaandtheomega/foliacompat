package xyz.vprolabs.foliacompat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

final class ObfHelperBridge {
    private ObfHelperBridge() {}

    static void resolveObfHelper() {
        try {
            Class<?> obfHelperClass = Class.forName("io.papermc.paper.util.ObfHelper");
            Field instanceField = obfHelperClass.getField("INSTANCE");
            ClassMapper.obfHelper = instanceField.get(null);
            if (ClassMapper.obfHelper == null) {
                ClassMapper.obfHelperResolved = false;
                return;
            }
            ClassMapper.obfMappingsMethod = obfHelperClass.getMethod("mappingsByObfName");
            Object mappings = ClassMapper.obfMappingsMethod.invoke(ClassMapper.obfHelper);
            if (mappings instanceof Map<?, ?> map) {
                ClassMapper.obfMappingsMap = (Map<String, Object>) map;
            }
            if (ClassMapper.obfMappingsMap != null && !ClassMapper.obfMappingsMap.isEmpty()) {
                var iter = ClassMapper.obfMappingsMap.values().iterator();
                if (!iter.hasNext()) {
                    ClassMapper.obfHelperResolved = false;
                    return;
                }
                Object sample = iter.next();
                ClassMapper.classMappingMojangNameMethod = sample.getClass().getMethod("mojangName");
                ClassMapper.obfHelperResolved = true;
                DebugUtil.info("ObfHelper resolved: " + ClassMapper.obfMappingsMap.size() + " class mappings");
            } else {
                ClassMapper.obfHelperResolved = false;
                if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper map empty during init");
            }
        } catch (ClassNotFoundException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper not available: " + e.getClass().getSimpleName());
        } catch (NoSuchFieldException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper INSTANCE field missing: " + e.getMessage());
        } catch (IllegalAccessException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper field access denied: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper method missing: " + e.getMessage());
        } catch (InvocationTargetException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper invoke failed: " + e.getCause());
        } catch (SecurityException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper security denied: " + e.getMessage());
        } catch (NullPointerException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper null state: " + e.getMessage());
        } catch (ClassCastException e) {
            ClassMapper.obfHelperResolved = false;
            if (ClassMapper.isDebugMode()) ClassMapper.log.fine("ObfHelper type mismatch: " + e.getMessage());
        }
    }

    static String mapViaObfHelper(String spigotName) {
        if (spigotName == null) {
            ClassMapper.log.fine("FC OBFSKIP null input");
            return null;
        }
        if (!ClassMapper.obfHelperResolved || ClassMapper.obfMappingsMap == null || ClassMapper.classMappingMojangNameMethod == null) {
            DebugUtil.info("FC OBFSKIP " + spigotName + " resolved=" + ClassMapper.obfHelperResolved + " map=" + (ClassMapper.obfMappingsMap != null ? ClassMapper.obfMappingsMap.size() : "null") + " method=" + (ClassMapper.classMappingMojangNameMethod != null));
            return null;
        }
        try {
            Object mapping = ClassMapper.obfMappingsMap.get(spigotName);
            if (mapping == null) mapping = ClassMapper.obfMappingsMap.get(spigotName.replace('.', '/'));
            if (mapping == null) {
                if (ClassMapper.isDebugMode()) DebugUtil.info("FC OBFMISS " + spigotName + " mapKeys=" + ClassMapper.obfMappingsMap.keySet().stream().filter(k -> k.contains("EntityHuman") || k.contains("EntityPlayer")).limit(2).collect(java.util.stream.Collectors.joining(",")));
                return null;
            }
            return (String) ClassMapper.classMappingMojangNameMethod.invoke(mapping);
        } catch (InvocationTargetException e) {
            ClassMapper.log.fine("FC OBFMAP invoke error: " + e.getCause());
            return null;
        } catch (IllegalAccessException e) {
            ClassMapper.log.fine("FC OBFMAP access error: " + e.getMessage());
            return null;
        } catch (ClassCastException e) {
            ClassMapper.log.fine("FC OBFMAP type error: " + e.getMessage());
            return null;
        } catch (NullPointerException e) {
            ClassMapper.log.fine("FC OBFMAP null error: " + e.getMessage());
            return null;
        } catch (SecurityException e) {
            ClassMapper.log.fine("FC OBFMAP security error: " + e.getMessage());
            return null;
        }
    }
}
