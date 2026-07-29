package xyz.vprolabs.foliacompat;

import org.bukkit.Bukkit;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class CraftBukkitMapper {
    private CraftBukkitMapper() {}

    static void resolveCraftBukkitFallbacks() {
        if (Bukkit.getServer() == null) {
            ClassMapper.log.warning("FC CBMAP server is null, skipping CraftBukkit fallback resolution");
            ClassMapper.cbRenameResolved = false;
            return;
        }
        ClassLoader serverLoader;
        try {
            serverLoader = Bukkit.getServer().getClass().getClassLoader();
            if (serverLoader == null) {
                ClassMapper.log.warning("FC CBMAP server classloader is null, using own classloader");
                serverLoader = CraftBukkitMapper.class.getClassLoader();
            }
        } catch (SecurityException e) {
            ClassMapper.log.warning("FC CBMAP cannot access server classloader: " + e.getMessage());
            serverLoader = CraftBukkitMapper.class.getClassLoader();
        } catch (NullPointerException e) {
            ClassMapper.log.warning("FC CBMAP server or class is null: " + e.getMessage());
            serverLoader = CraftBukkitMapper.class.getClassLoader();
        }
        if (serverLoader == null) {
            ClassMapper.log.warning("FC CBMAP no classloader available, using hardcoded fallback only");
            applyFallback();
            return;
        }

        String craftVersion = ClassMapper.detectCraftBukkitVersion();
        if (craftVersion == null) {
            ClassMapper.log.warning("FC CBMAP could not detect CraftBukkit version");
            ClassMapper.cbRenameResolved = false;
            return;
        }
        String pkg = "org.bukkit.craftbukkit." + craftVersion + ".entity.";

        // Probe entity classes: for each known entity name, try candidates in order
        // First probe that the base classes exist, then define fallbacks for removed ones.
        Map<String, String[]> probes = new LinkedHashMap<>();
        probes.put("CraftEntity", new String[]{null});  // always present, no redirect needed
        probes.put("CraftHumanEntity", new String[]{"CraftEntity", "CraftPlayer"});
        probes.put("CraftLivingEntity", new String[]{"CraftEntity", "CraftCreature"});
        probes.put("CraftCreature", new String[]{"CraftLivingEntity", "CraftEntity"});
        probes.put("CraftMonster", new String[]{"CraftCreature", "CraftEntity"});
        probes.put("CraftPlayer", new String[]{"CraftHumanEntity", "CraftEntity"});
        probes.put("CraftAnimal", new String[]{"CraftEntity"});

        for (Map.Entry<String, String[]> entry : probes.entrySet()) {
            String spigotName = entry.getKey();
            String[] candidates = entry.getValue();
            if (candidates == null || candidates.length == 0) continue;
            if (candidates[0] == null) continue;  // CraftEntity: known to exist, skip

            boolean resolved = false;
            for (String candidate : candidates) {
                try {
                    Class<?> targetClass = Class.forName(pkg + candidate, false, serverLoader);
                    ClassMapper.craftBukkitRenameMap.put(pkg + spigotName, pkg + candidate);
                    ClassMapper.craftBukkitRedirectClass = targetClass;
                    DebugUtil.info("FC CBMAP " + spigotName + " -> " + candidate);
                    resolved = true;
                    break;
                } catch (ClassNotFoundException e) {
                    DebugUtil.info("FC CBMAP probe " + candidate + " not found: " + e.getMessage());
                } catch (SecurityException e) {
                    DebugUtil.info("FC CBMAP security for " + candidate + ": " + e.getMessage());
                } catch (NullPointerException e) {
                    DebugUtil.info("FC CBMAP null for " + candidate + ": " + e.getMessage());
                }
            }
            if (!resolved) {
                String synthName = pkg + spigotName;
                String targetFirst = pkg + candidates[0];
                ClassMapper.craftBukkitRenameMap.put(synthName, targetFirst);
                DebugUtil.info("FC CBMAP " + spigotName + " -> " + candidates[0] + " (hardcoded fallback)");
            }
        }

        ClassMapper.cbRenameResolved = !ClassMapper.craftBukkitRenameMap.isEmpty();
        ClassMapper.craftBukkitRenameMap.forEach((k, v) -> DebugUtil.info("FC CBMAPKEY " + k + " -> " + v));
    }


    static byte[] generateSyntheticBytes(String internalName, String superInternalName) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(0xCAFEBABE);
        dos.writeShort(0);
        dos.writeShort(65);
        byte[] tn = internalName.getBytes(StandardCharsets.UTF_8);
        byte[] sn = superInternalName.getBytes(StandardCharsets.UTF_8);
        byte[] initName = "<init>".getBytes(StandardCharsets.UTF_8);
        byte[] voidDesc = "()V".getBytes(StandardCharsets.UTF_8);
        byte[] codeAttr = "Code".getBytes(StandardCharsets.UTF_8);
        int idx = 1;
        dos.writeShort(10);
        dos.writeByte(1); dos.writeShort(tn.length); dos.write(tn); idx++;
        dos.writeByte(7); dos.writeShort(1); idx++;
        dos.writeByte(1); dos.writeShort(sn.length); dos.write(sn); idx++;
        dos.writeByte(7); dos.writeShort(3); idx++;
        dos.writeByte(1); dos.writeShort(initName.length); dos.write(initName); idx++;
        dos.writeByte(1); dos.writeShort(voidDesc.length); dos.write(voidDesc); idx++;
        dos.writeByte(1); dos.writeShort(codeAttr.length); dos.write(codeAttr); idx++;
        int natIdx = idx;
        dos.writeByte(12); dos.writeShort(5); dos.writeShort(6); idx++;
        int methodRefIdx = idx;
        dos.writeByte(10); dos.writeShort(4); dos.writeShort(natIdx); idx++;
        dos.writeShort(0x0021);
        dos.writeShort(2);
        dos.writeShort(4);
        dos.writeShort(0);
        dos.writeShort(0);
        dos.writeShort(1);
        dos.writeShort(0x0001);
        dos.writeShort(5);
        dos.writeShort(6);
        dos.writeShort(1);
        dos.writeShort(7);
        dos.writeInt(17);
        dos.writeShort(1);
        dos.writeShort(1);
        dos.writeInt(5);
        dos.writeByte(0x2A);
        dos.writeByte(0xB7);
        dos.writeShort(methodRefIdx);
        dos.writeByte(0xB1);
        dos.writeShort(0);
        dos.writeShort(0);
        dos.writeShort(0);
        return baos.toByteArray();
    }

    private static void applyFallback() {
        ClassMapper.craftBukkitRenameMap.put(
            ClassMapper.craftBukkitVersion != null
                ? "org.bukkit.craftbukkit." + ClassMapper.craftBukkitVersion + ".entity.CraftHumanEntity"
                : "org.bukkit.craftbukkit.v1_21_R3.entity.CraftHumanEntity",
            ClassMapper.craftBukkitVersion != null
                ? "org.bukkit.craftbukkit." + ClassMapper.craftBukkitVersion + ".entity.CraftEntity"
                : "org.bukkit.craftbukkit.v1_21_R3.entity.CraftEntity");
        ClassMapper.cbRenameResolved = true;
        DebugUtil.info("FC CBMAP CraftHumanEntity -> CraftEntity (classloader-less fallback)");
    }
}
