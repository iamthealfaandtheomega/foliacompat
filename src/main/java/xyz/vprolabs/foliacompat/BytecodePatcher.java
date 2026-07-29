package xyz.vprolabs.foliacompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class BytecodePatcher {
    public static final Logger log = Logger.getLogger("FoliaCompat");

    private static final Set<String> INTERFACE_CLASS_NAMES = new HashSet<>();

    public static void initInterfaceClasses() {
        try {
            if (java.lang.reflect.Modifier.isInterface(org.bukkit.Sound.class.getModifiers())) {
                INTERFACE_CLASS_NAMES.add("org/bukkit/Sound");
                DebugUtil.info("FC IFACE Sound is an interface");
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError) throw t;
            log.fine("FC IFACE Sound probe failed: " + t.getClass().getSimpleName());
        }
    }

    private BytecodePatcher() {}

    public static String replaceNmsRefs(String str, Map<String, String> nmsMap) {
        if (str == null) return null;
        if (nmsMap == null || !str.contains("net/minecraft/")) return str;
        return replacePrefix(str, nmsMap, "net/minecraft/");
    }

    public static String replaceCbRefs(String str, Map<String, String> cbMap) {
        if (str == null) return null;
        if (cbMap == null || !str.contains("org/bukkit/craftbukkit/")) return str;
        return replacePrefix(str, cbMap, "org/bukkit/craftbukkit/");
    }

    private static String replacePrefix(String str, Map<String, String> map, String prefix) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < str.length()) {
            int idx = str.indexOf(prefix, i);
            if (idx < 0) { sb.append(str.substring(i)); break; }
            sb.append(str.substring(i, idx));
            int end = idx;
            while (end < str.length()) {
                char c = str.charAt(end);
                if (c == ';' || c == '(' || c == ')' || c == '[' || c == ' ' || c == '<') break;
                end++;
            }
            String fullClass = str.substring(idx, end);
            String dotName = fullClass.replace('/', '.');
            String mapped = map.get(dotName);
            if (mapped != null && !mapped.equals(dotName)) {
                sb.append(mapped.replace('.', '/'));
            } else {
                sb.append(fullClass);
            }
            i = end;
        }
        return sb.toString();
    }

    public static byte[] patchBytecode(byte[] bytes, Map<String, String> nmsMap) {
        return patchBytecode(bytes, nmsMap, null);
    }

    public static byte[] patchBytecode(byte[] bytes, Map<String, String> nmsMap, Map<String, String> cbMap) {
        if (bytes == null) return null;
        if (bytes.length < 8 || bytes[0] != (byte)0xCA || bytes[1] != (byte)0xFE
                || bytes[2] != (byte)0xBA || bytes[3] != (byte)0xBE) {
            return bytes;
        }
        boolean hasNms = nmsMap != null && !nmsMap.isEmpty();
        boolean hasCb = cbMap != null && !cbMap.isEmpty();
        if (!hasNms && !hasCb && INTERFACE_CLASS_NAMES.isEmpty()) return bytes;
        log.fine("FC PATCHCALL nmsMap.size=" + (nmsMap != null ? nmsMap.size() : 0)
            + " cbMap.size=" + (cbMap != null ? cbMap.size() : 0));
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
            ByteArrayOutputStream baos = new ByteArrayOutputStream(bytes.length + 256);
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeInt(dis.readInt());
            dos.writeShort(dis.readUnsignedShort());
            dos.writeShort(dis.readUnsignedShort());

            int cpCount = dis.readUnsignedShort();
            dos.writeShort(cpCount);

            byte[] tags = new byte[cpCount];
            byte[][] rawData = new byte[cpCount][];

            int idx = 1;
            while (idx < cpCount) {
                int tag = dis.readUnsignedByte();
                tags[idx] = (byte) tag;

                switch (tag) {
                    case 1: {
                        int len = dis.readUnsignedShort();
                        byte[] data = new byte[len];
                        dis.readFully(data);
                        rawData[idx] = data;
                        break;
                    }
                    case 5:
                    case 6: {
                        rawData[idx] = new byte[] {
                                dis.readByte(), dis.readByte(), dis.readByte(), dis.readByte(),
                                dis.readByte(), dis.readByte(), dis.readByte(), dis.readByte()
                        };
                        tags[idx + 1] = 0;
                        idx++;
                        break;
                    }
                    case 3:
                    case 4:
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                    {
                        rawData[idx] = new byte[] {
                                dis.readByte(), dis.readByte(), dis.readByte(), dis.readByte()
                        };
                        break;
                    }
                    case 15: {
                        rawData[idx] = new byte[] {
                                dis.readByte(), dis.readByte(), dis.readByte()
                        };
                        break;
                    }
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                    {
                        rawData[idx] = new byte[] {
                                dis.readByte(), dis.readByte()
                        };
                        break;
                    }
                    default:
                        return bytes;
                }
                idx++;
            }

            for (int i = 1; i < cpCount; i++) {
                if (tags[i] == 1) {
                    String str = new String(rawData[i], StandardCharsets.UTF_8);
                    String patched = str;
                    if (hasNms) patched = replaceNmsRefs(patched, nmsMap);
                    if (hasCb) patched = replaceCbRefs(patched, cbMap);
                    if (!patched.equals(str)) {
                        rawData[i] = patched.getBytes(StandardCharsets.UTF_8);
                    }
                }
            }

            if (!INTERFACE_CLASS_NAMES.isEmpty()) {
                for (int i = 1; i < cpCount; i++) {
                    if (tags[i] == 10) {
                        int classIndex = ((rawData[i][0] & 0xFF) << 8) | (rawData[i][1] & 0xFF);
                        if (classIndex > 0 && classIndex < cpCount && tags[classIndex] == 7) {
                            int nameIndex = ((rawData[classIndex][0] & 0xFF) << 8) | (rawData[classIndex][1] & 0xFF);
                            if (nameIndex > 0 && nameIndex < cpCount && tags[nameIndex] == 1) {
                                String className = new String(rawData[nameIndex], StandardCharsets.UTF_8);
                                if (INTERFACE_CLASS_NAMES.contains(className)) {
                                    tags[i] = 11;
                                    log.fine("FC IFACE converted Methodref→InterfaceMethodref for " + className);
                                }
                            }
                        }
                    }
                }
            }

            for (int i = 1; i < cpCount; i++) {
                if (tags[i] == 0) continue;
                dos.writeByte(tags[i]);
                if (tags[i] == 1) {
                    dos.writeShort(rawData[i].length);
                    dos.write(rawData[i]);
                } else {
                    dos.write(rawData[i]);
                }
                if (tags[i] == 5 || tags[i] == 6) {
                    i++;
                }
            }

            byte[] rest = dis.readAllBytes();
            dos.write(rest);
            dos.flush();

            return baos.toByteArray();
        } catch (EOFException e) {
            log.fine("FC PATCHERR eof " + e.getMessage());
            return bytes;
        } catch (IOException e) {
            log.fine("FC PATCHERR io " + e.getMessage());
            return bytes;
        } catch (ArrayIndexOutOfBoundsException e) {
            log.fine("FC PATCHERR array " + e.getMessage());
            return bytes;
        } catch (NullPointerException e) {
            log.fine("FC PATCHERR npe " + e.getMessage());
            return bytes;
        }
    }
}
