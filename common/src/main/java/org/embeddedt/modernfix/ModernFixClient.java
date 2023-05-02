package org.embeddedt.modernfix;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MemoryReserve;
import net.minecraft.world.entity.Entity;
import org.embeddedt.modernfix.core.ModernFixMixinPlugin;
import org.embeddedt.modernfix.packet.EntityIDSyncPacket;
import org.embeddedt.modernfix.platform.ModernFixPlatformHooks;
import org.embeddedt.modernfix.world.IntegratedWatchdog;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.*;

public class ModernFixClient {
    public static long worldLoadStartTime;
    private static int numRenderTicks;

    public static float gameStartTimeSeconds = -1;

    private static boolean recipesUpdated, tagsUpdated = false;

    public String brandingString = null;

    public ModernFixClient() {
        // clear reserve as it's not needed
        MemoryReserve.release();
        if(ModernFixMixinPlugin.instance.isOptionEnabled("feature.branding.F3Screen")) {
            brandingString = "ModernFix " + ModernFixPlatformHooks.getVersionString();
        }
    }

    public void resetWorldLoadStateMachine() {
        numRenderTicks = 0;
        worldLoadStartTime = -1;
        recipesUpdated = false;
        tagsUpdated = false;
    }

    public void onScreenOpening(Screen openingScreen) {
        if(openingScreen instanceof ConnectScreen) {
            worldLoadStartTime = System.nanoTime();
        } else if (openingScreen instanceof TitleScreen && gameStartTimeSeconds < 0) {
            gameStartTimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000f;
            ModernFix.LOGGER.warn("Game took " + gameStartTimeSeconds + " seconds to start");
        }
    }

    public void onRecipesUpdated() {
        recipesUpdated = true;
    }

    public void onTagsUpdated() {
        tagsUpdated = true;
    }

    public void onRenderTickEnd() {
        if(recipesUpdated
                && tagsUpdated
                && worldLoadStartTime != -1
                && Minecraft.getInstance().player != null
                && numRenderTicks++ >= 10) {
            float timeSpentLoading = ((float)(System.nanoTime() - worldLoadStartTime) / 1000000000f);
            ModernFix.LOGGER.warn("Time from main menu to in-game was " + timeSpentLoading + " seconds");
            ModernFix.LOGGER.warn("Total time to load game and open world was " + (timeSpentLoading + gameStartTimeSeconds) + " seconds");
            resetWorldLoadStateMachine();
        }
    }

    /**
     * Check if the IDs match and remap them if not.
     * @return true if ID remap was needed
     */
    private static boolean compareAndSwitchIds(Class<? extends Entity> eClass, String fieldName, EntityDataAccessor<?> accessor, int newId) {
        if(accessor.id != newId) {
            ModernFix.LOGGER.warn("Corrected ID mismatch on {} field {}. Client had {} but server wants {}.",
                    eClass,
                    fieldName,
                    accessor.id,
                    newId);
            accessor.id = newId;
            return true;
        } else {
            ModernFix.LOGGER.debug("{} {} ID fine: {}", eClass, fieldName, newId);
            return false;
        }
    }

    /**
     * Horrendous hack to allow tracking every synced entity data manager.
     *
     * This is to ensure we can perform ID fixup on already constructed managers.
     */
    public static final Set<SynchedEntityData> allEntityDatas = Collections.newSetFromMap(new WeakHashMap<>());

    private static final Field entriesArrayField;
    static {
        Field field;
        try {
            field = SynchedEntityData.class.getDeclaredField("entriesArray");
            field.setAccessible(true);
        } catch(ReflectiveOperationException e) {
            field = null;
        }
        entriesArrayField = field;
    }

    /**
     * Extremely hacky method to detect and correct mismatched entity data parameter IDs on the client and server.
     *
     * The technique is far from ideal, but it should detect reliably and also not break already constructed entities.
     */
    public static void handleEntityIDSync(EntityIDSyncPacket packet) {
        Map<Class<? extends Entity>, List<Pair<String, Integer>>> info = packet.getFieldInfo();
        boolean fixNeeded = false;
        for(Map.Entry<Class<? extends Entity>, List<Pair<String, Integer>>> entry : info.entrySet()) {
            Class<? extends Entity> eClass = entry.getKey();
            for(Pair<String, Integer> field : entry.getValue()) {
                String fieldName = field.getFirst();
                int newId = field.getSecond();
                try {
                    Field f = eClass.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    EntityDataAccessor<?> accessor = (EntityDataAccessor<?>)f.get(null);
                    if(compareAndSwitchIds(eClass, fieldName, accessor, newId))
                        fixNeeded = true;
                } catch(NoSuchFieldException e) {
                    ModernFix.LOGGER.warn("Couldn't find field on {}: {}", eClass, fieldName);
                } catch(ReflectiveOperationException e) {
                    throw new RuntimeException("Unexpected exception", e);
                }
            }
        }
        /* Now the ID mappings on synced entity data instances are probably all wrong. Fix that. */
        List<SynchedEntityData> dataEntries;
        synchronized (allEntityDatas) {
            if(fixNeeded) {
                dataEntries = new ArrayList<>(allEntityDatas);
                for(SynchedEntityData manager : dataEntries) {
                    Int2ObjectOpenHashMap<SynchedEntityData.DataItem<?>> fixedMap = new Int2ObjectOpenHashMap<>();
                    List<SynchedEntityData.DataItem<?>> items = new ArrayList<>(manager.itemsById.values());
                    for(SynchedEntityData.DataItem<?> item : items) {
                        fixedMap.put(item.getAccessor().id, item);
                    }
                    manager.lock.writeLock().lock();
                    try {
                        manager.itemsById.replaceAll((id, parameter) -> fixedMap.get((int)id));
                        if(entriesArrayField != null) {
                            try {
                                SynchedEntityData.DataItem<?>[] dataArray = new SynchedEntityData.DataItem[items.size()];
                                for(int i = 0; i < dataArray.length; i++) {
                                    dataArray[i] = fixedMap.get(i);
                                }
                                entriesArrayField.set(manager, dataArray);
                            } catch(ReflectiveOperationException e) {
                                ModernFix.LOGGER.error(e);
                            }
                        }
                    } finally {
                        manager.lock.writeLock().unlock();
                    }
                }
            }
            allEntityDatas.clear();
        }
    }

    public void onServerStarted(MinecraftServer server) {
        IntegratedWatchdog watchdog = new IntegratedWatchdog(server);
        watchdog.start();
    }
}
