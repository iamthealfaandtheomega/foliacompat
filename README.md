<div align="center">

<h1>FoliaCompat</h1>

[![Version](https://img.shields.io/modrinth/v/foliacompat?label=Version&color=24b47e)](https://modrinth.com/plugin/foliacompat)
[![Downloads](https://img.shields.io/modrinth/dt/foliacompat?label=Downloads&color=24b47e)](https://modrinth.com/plugin/foliacompat)
[![License](https://img.shields.io/badge/License-PolyForm%20Noncommercial%201.0-blue)](https://polyformproject.org/licenses/noncommercial/1.0.0)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.oracle.com/java/)
[![Platform](https://img.shields.io/badge/Platform-Folia-red)](https://papermc.io/software/folia)

<p><strong>Add Bukkit & PaperMC Plugins Support For Folia!</strong></p>

</div>

---

### Mission

Make every existing Bukkit and Paper plugin work on Folia — zero code changes, zero plugin modifications, zero excuses.

FoliaCompat is a zero-dependency drop-in plugin that bridges every Bukkit/Paper API difference to Folia's threaded region architecture — detected and handled at runtime. **Works on all versions of Folia.**

### Features

| Feature | Status | Notes |
|---------|--------|-------|
| Scheduler bridge (runTask, scheduleSync*, etc.) | ✅ Works | All `BukkitScheduler` methods wrapped, delay/period clamped, runtime ASM bridge for field type safety |
| Scheduler injection (onLoad/onDisable) | ✅ Works | Reflection + Unsafe fallback; re-injected if Folia reverts the field |
| Dynamic bridge class generation | ✅ Works | Extends `CraftScheduler` at runtime via ASM + `MethodHandles.defineClass()` — type-safe, no vtable corruption, works on JDK 21/25 |
| Shutdown crash protection (SIGSEGV) | ✅ Fixed | Bridge class eliminates vtable mismatch — no more `LinkResolver::runtime_resolve_virtual_method` crashes |
| Shutdown NPE protection | ✅ Fixed | Daemon watcher + instance protection + re-injection; no more `CraftScheduler.getActiveWorkers()` null |
| Plugin bytecode patching (NMS → mapped names) | ✅ Works | Constant pool Utf8 rewriting at `findClass()` time via custom classloader |
| Plugin bytecode patching (CraftBukkit renames) | ⚠️ Experimental | Runtime-probed fallback map + hardcoded fallbacks; version-dependent |
| Synthetic CraftBukkit entity classes | ⚠️ Experimental | `CraftHumanEntity` → `CraftEntity`, `CraftLivingEntity` → `CraftEntity`; hierarchy guessed, some edge cases |
| ObfHelper / reobf.tiny mapping resolution | ⚠️ Experimental | Lazy-init handling and `mappingsByObfName()` API; depends on ObfHelper availability |
| Plugin cache (skip re-patch on restart) | ✅ Works | Patched bytes cached to disk |
| Unsafe plugin constructor bypass | 🟡 In-work | `Unsafe.allocateInstance()` + null collection field init; cascade patterns still being fixed |
| `/fc load`, `/fc unload`, `/fc reload`, `/fc list` commands | ✅ Works | With tab completion and `foliacompat.admin` permission |
| Full class hierarchy coverage | 🟡 In-work | `CraftHumanEntity`, `CraftLivingEntity`, others generated on demand; experimental, may have errors |




### Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/fc load <filename.jar>` | Load and enable a plugin from the FoliaCompat plugins directory | `foliacompat.admin` |
| `/fc unload <pluginname>` | Disable and unload a managed plugin | `foliacompat.admin` |
| `/fc reload <pluginname>` | Unload then reload a managed plugin | `foliacompat.admin` |
| `/fc list` | List all managed plugins and their status | `foliacompat.admin` |

*Alias: `/foliacompat` = `/fc`*

---

### Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `foliacompat.admin` | Allows use of all `/foliacompat` subcommands | op |

---

### Configuration

<details>
<summary><b>View config.yml</b></summary>

```yaml
# --- Internal config version ---
config-version: 4

# --- Plugin cache ---
reset-cache-on-restart: false

# --- Error reporting ---
error-reporting:
  enabled: true

# --- Modrinth update checking ---
modrinth-update-check: true

# --- Debug logging ---
debug: false
```

</details>

---



### Tested Plugins

The following plugins have been tested and work correctly:
- InvSeePlusPlus
- DropHead
- ServerRedirect
- DimensionControl
- Discord-Linker
- SimpleWarps
- AzAuctions
- KitsPlugin
- ManhuntPlus
- WaystoneWarps
- XaeroForceDisabler
- LifestealSMP
- Nametagsplusplus
- PlayerSit
- CarryOn
- Border
- Strings
and many more!

---

### Known Limitations

- **Only `net.minecraft.*` and `org.bukkit.craftbukkit.*` class references are patched.** Plugins using JNI, native code, or advanced Paper-specific APIs may not work.
- **Synthetic entity classes provide only inheritance structure.** Plugin code that directly instantiates CraftBukkit entity constructors will fail.
- **Folia's native region schedulers are not exposed via API.** The bridge wraps Bukkit scheduler methods only; plugins using Folia's `RegionScheduler` or `EntityScheduler` directly are not intercepted.

---

<div align="center">
  <a href="https://www.vprolabs.xyz/foliumhosting">
    <img src="https://cdn.modrinth.com/data/cached_images/4a06749284b8ac33f9754f15990dee97e9d57892.png" alt="FoliumHosting">
  </a>
  <h2>
    <a href="https://www.vprolabs.xyz/foliumhosting">Check out FoliumHosting!</a>
  </h2>
</div>

---

### Links

- 🌐 **Website:** https://vprolabs.xyz
- 💬 **Discord:** https://discord.vprolabs.xyz
- 📦 **Modrinth:** https://modrinth.com/plugin/foliacompat
- ☕ **Support:** https://ko-fi.com/iamthealfaandtheomega

---

### License

This project is licensed under the **PolyForm Noncommercial License 1.0.0**.

- Non-Commercial Use Only
- [View Full License](https://polyformproject.org/licenses/noncommercial/1.0.0)

---

<div align="center">

<sub>Made with 🔥 by <strong>vProLabs</strong></sub>

</div>
