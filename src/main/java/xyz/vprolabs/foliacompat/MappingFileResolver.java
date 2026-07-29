package xyz.vprolabs.foliacompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

final class MappingFileResolver {
    private MappingFileResolver() {}

    static void resolveMappingFile() {
        String resPath = "META-INF/mappings/reobf.tiny";
        InputStream in = null;
        String src = null;

        try {
            ClassLoader sl = org.bukkit.Bukkit.getServer().getClass().getClassLoader();
            if (sl != null) { in = sl.getResourceAsStream(resPath); if (in != null) src = "srv"; }
        } catch (SecurityException | NullPointerException e) {
            ClassMapper.log.fine("resolveMappingFile: serverLoader: " + e.getClass().getSimpleName());
        }
        if (in == null) {
            try {
                ClassLoader tl = Thread.currentThread().getContextClassLoader();
                if (tl != null) { in = tl.getResourceAsStream(resPath); if (in != null) src = "tcl"; }
            } catch (SecurityException | NullPointerException e) {
                ClassMapper.log.fine("resolveMappingFile: TCCL: " + e.getClass().getSimpleName());
            }
        }
        if (in == null) {
            try {
                ClassLoader sys = ClassLoader.getSystemClassLoader();
                if (sys != null) { in = sys.getResourceAsStream(resPath); if (in != null) src = "sys"; }
            } catch (SecurityException | NullPointerException e) {
                ClassMapper.log.fine("resolveMappingFile: systemLoader: " + e.getClass().getSimpleName());
            }
        }
        if (in == null) {
            try {
                ClassLoader own = ClassMapper.class.getClassLoader();
                if (own != null) { in = own.getResourceAsStream(resPath); if (in != null) src = "own"; }
            } catch (SecurityException | NullPointerException e) {
                ClassMapper.log.fine("resolveMappingFile: ownLoader: " + e.getClass().getSimpleName());
            }
        }

        if (in == null) {
            DebugUtil.info("reobf.tiny not found — using hardcoded fallback mappings");
            initHardcodedMappings();
            return;
        }
        DebugUtil.info("reobf.tiny resolved via: " + src);
        Map<String, String> map = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            String h1 = null;
            String h2 = null;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] parts = line.split("\t");
                if (parts[0].equals("tiny")) {
                    if (parts.length >= 5) { h1 = parts[3]; h2 = parts[4]; }
                    continue;
                }
                if (parts[0].equals("c") && parts.length >= 3) {
                    String first = parts[1].replace('/', '.');
                    String second = parts[2].replace('/', '.');
                    if (h1 != null && h1.equals("spigot") && "mojang".equals(h2)) {
                        map.put(first, second);
                    } else {
                        map.put(second, first);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException | ArrayIndexOutOfBoundsException | NullPointerException e) {
            ClassMapper.log.warning("reobf.tiny parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            initHardcodedMappings();
            return;
        }
        ClassMapper.spigotToMojangMap = map;
        ClassMapper.mappingFileResolved = true;
        DebugUtil.info("reobf.tiny parsed: " + map.size() + " class mappings loaded");
        DebugUtil.info("FC TINYCHECK contains EntityHuman=" + map.containsKey("net.minecraft.world.entity.player.EntityHuman"));
    }

    static void initHardcodedMappings() {
        Map<String, String> map = new HashMap<>();
        map.put("net.minecraft.world.entity.player.EntityHuman", "net.minecraft.world.entity.player.Player");
        map.put("net.minecraft.server.level.EntityPlayer", "net.minecraft.server.level.ServerPlayer");
        map.put("net.minecraft.world.entity.EntityLiving", "net.minecraft.world.entity.LivingEntity");
        map.put("net.minecraft.world.entity.item.EntityItem", "net.minecraft.world.entity.item.ItemEntity");
        map.put("net.minecraft.world.entity.monster.EntityZombie", "net.minecraft.world.entity.monster.Zombie");
        map.put("net.minecraft.world.entity.monster.EntityCreeper", "net.minecraft.world.entity.monster.Creeper");
        map.put("net.minecraft.world.entity.monster.EntitySkeleton", "net.minecraft.world.entity.monster.Skeleton");
        map.put("net.minecraft.world.level.World", "net.minecraft.world.level.Level");
        map.put("net.minecraft.server.level.WorldServer", "net.minecraft.server.level.ServerLevel");
        map.put("net.minecraft.server.level.PlayerChunkMap", "net.minecraft.server.level.ChunkMap");
        map.put("net.minecraft.world.level.chunk.Chunk", "net.minecraft.world.level.chunk.LevelChunk");
        map.put("net.minecraft.world.level.chunk.IChunkAccess", "net.minecraft.world.level.chunk.ChunkAccess");
        map.put("net.minecraft.world.level.block.state.IBlockData", "net.minecraft.world.level.block.state.BlockState");
        map.put("net.minecraft.core.BlockPosition", "net.minecraft.core.BlockPos");
        map.put("net.minecraft.world.phys.Vec3D", "net.minecraft.world.phys.Vec3");
        map.put("net.minecraft.world.phys.AxisAlignedBB", "net.minecraft.world.phys.AABB");
        map.put("net.minecraft.util.MathHelper", "net.minecraft.util.Mth");
        map.put("net.minecraft.nbt.NBTTagCompound", "net.minecraft.nbt.CompoundTag");
        map.put("net.minecraft.nbt.NBTTagList", "net.minecraft.nbt.ListTag");
        map.put("net.minecraft.nbt.NBTTagByte", "net.minecraft.nbt.ByteTag");
        map.put("net.minecraft.nbt.NBTTagShort", "net.minecraft.nbt.ShortTag");
        map.put("net.minecraft.nbt.NBTTagInt", "net.minecraft.nbt.IntTag");
        map.put("net.minecraft.nbt.NBTTagLong", "net.minecraft.nbt.LongTag");
        map.put("net.minecraft.nbt.NBTTagFloat", "net.minecraft.nbt.FloatTag");
        map.put("net.minecraft.nbt.NBTTagDouble", "net.minecraft.nbt.DoubleTag");
        map.put("net.minecraft.nbt.NBTTagString", "net.minecraft.nbt.StringTag");
        map.put("net.minecraft.nbt.NBTTagByteArray", "net.minecraft.nbt.ByteArrayTag");
        map.put("net.minecraft.nbt.NBTTagIntArray", "net.minecraft.nbt.IntArrayTag");
        map.put("net.minecraft.network.chat.IChatBaseComponent", "net.minecraft.network.chat.Component");
        map.put("net.minecraft.server.network.PlayerConnection", "net.minecraft.server.network.ServerGamePacketListenerImpl");
        map.put("net.minecraft.world.inventory.Container", "net.minecraft.world.inventory.AbstractContainerMenu");
        map.put("net.minecraft.world.inventory.ContainerAccess", "net.minecraft.world.inventory.ContainerLevelAccess");
        map.put("net.minecraft.world.level.block.entity.TileEntity", "net.minecraft.world.level.block.entity.BlockEntity");
        map.put("net.minecraft.world.level.block.entity.TileEntityChest", "net.minecraft.world.level.block.entity.ChestBlockEntity");
        map.put("net.minecraft.world.level.block.entity.TileEntityFurnace", "net.minecraft.world.level.block.entity.FurnaceBlockEntity");
        map.put("net.minecraft.world.level.block.entity.TileEntitySign", "net.minecraft.world.level.block.entity.SignBlockEntity");
        map.put("net.minecraft.world.level.block.entity.TileEntitySkull", "net.minecraft.world.level.block.entity.SkullBlockEntity");
        map.put("net.minecraft.world.entity.ai.attributes.GenericAttributes", "net.minecraft.world.entity.ai.attributes.Attributes");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutChat", "net.minecraft.network.protocol.game.ClientboundChatPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutTitle", "net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutPlayerListHeaderFooter", "net.minecraft.network.protocol.game.ClientboundTabListPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutNamedEntitySpawn", "net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutEntityMetadata", "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutSpawnEntityLiving", "net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutBlockBreakAnimation", "net.minecraft.network.protocol.game.ClientboundBlockBreakAnimationPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutBlockChange", "net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutAnimation", "net.minecraft.network.protocol.game.ClientboundAnimatePacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutEntityEffect", "net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutRemoveEntityEffect", "net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutExperience", "net.minecraft.network.protocol.game.ClientboundSetExperiencePacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutKeepAlive", "net.minecraft.network.protocol.game.ClientboundKeepAlivePacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutEntityVelocity", "net.minecraft.network.protocol.game.ClientboundSetEntityVelocityPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutEntityTeleport", "net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutExplosion", "net.minecraft.network.protocol.game.ClientboundExplodePacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutMapChunk", "net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
        map.put("net.minecraft.network.protocol.game.PacketPlayOutWorldEvent", "net.minecraft.network.protocol.game.ClientboundLevelEventPacket");
        map.put("net.minecraft.resources.MinecraftKey", "net.minecraft.resources.ResourceLocation");
        map.put("net.minecraft.core.IRegistry", "net.minecraft.core.Registry");
        map.put("net.minecraft.resources.ResourceKey", "net.minecraft.resources.ResourceKey");
        map.put("net.minecraft.world.phys.MovingObjectPositionBlock", "net.minecraft.world.phys.BlockHitResult");
        map.put("net.minecraft.world.phys.MovingObjectPosition", "net.minecraft.world.phys.HitResult");
        map.put("net.minecraft.world.entity.EntityPose", "net.minecraft.world.entity.Pose");
        map.put("net.minecraft.commands.CommandListenerWrapper", "net.minecraft.commands.CommandSourceStack");
        map.put("net.minecraft.commands.CommandDispatcher", "net.minecraft.commands.CommandDispatcher");
        map.put("net.minecraft.server.MinecraftServer", "net.minecraft.server.MinecraftServer");
        map.put("net.minecraft.world.entity.Entity", "net.minecraft.world.entity.Entity");
        map.put("net.minecraft.world.level.block.Block", "net.minecraft.world.level.block.Block");
        map.put("net.minecraft.world.item.ItemStack", "net.minecraft.world.item.ItemStack");
        map.put("net.minecraft.network.protocol.Packet", "net.minecraft.network.protocol.Packet");
        ClassMapper.spigotToMojangMap = map;
        ClassMapper.mappingFileResolved = true;
        DebugUtil.info("Hardcoded fallback mappings initialized: " + map.size() + " entries");
    }

    static String mapViaMappingFile(String spigotName) {
        if (spigotName == null) {
            ClassMapper.log.fine("FC TINYMISS null input");
            return null;
        }
        if (!ClassMapper.mappingFileResolved || ClassMapper.spigotToMojangMap == null) return null;
        String result = ClassMapper.spigotToMojangMap.get(spigotName);
        if (result == null) result = ClassMapper.spigotToMojangMap.get(spigotName.replace('.', '/'));
        if (result == null) {
            boolean hasDot = ClassMapper.spigotToMojangMap.containsKey("net.minecraft.world.entity.player.EntityHuman");
            boolean hasSlash = ClassMapper.spigotToMojangMap.containsKey("net/minecraft/world/entity/player/EntityHuman");
            if (ClassMapper.isDebugMode()) DebugUtil.info("FC TINYMISS " + spigotName + " hasDot=" + hasDot + " hasSlash=" + hasSlash + " total=" + ClassMapper.spigotToMojangMap.size());
        }
        return result;
    }
}
