package dev.stagelock.client.viewer;

import dev.stagelock.StageLock;
import dev.stagelock.client.ClientStageCache;
import dev.stagelock.client.MobDiscoveries;
import dev.stagelock.core.config.ServerConfig;
import dev.stagelock.core.restriction.RestrictionManager;
import dev.stagelock.core.restriction.ViewerHiding;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tells viewer integrations to recompute what they hide, but only when that can actually change.
 * <p>
 * Every tick it fingerprints the viewer-hiding rules in effect for the local player
 * ({@link ViewerHiding}); a stage change, a creative toggle or a rule reload that leaves the
 * fingerprint the same triggers nothing. With no {@code viewer: hide} rules at all, viewers are never
 * refreshed. A real change is debounced, so a burst of grants rebuilds JEI/REI/EMI once. (EMI's
 * refresh is a full reload, so skipping no-op refreshes matters.)
 * <p>
 * With {@code viewers.mobLootDiscovery} on and a loot mod installed, the first kill of each mob refreshes too.
 */
public final class ViewerRefresher {

    private static final int DEBOUNCE_TICKS = 5;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** What the viewers currently reflect. Viewers build from scratch on (re)load, which matches NONE or the live state. */
    // EMI calls markApplied() from its reload thread.
    private static volatile ViewerHiding applied = ViewerHiding.NONE;
    private static volatile int countdown = -1;
    private static volatile ServerConfig.RecipeHiding appliedMode = ServerConfig.RecipeHiding.SLOT;
    /** The killed mobs the viewers reflect, or null while mob loot pages are not being hidden. */
    private static volatile Set<ResourceLocation> appliedMobs;

    private ViewerRefresher() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            applied = ViewerHiding.NONE;
            appliedMobs = null;
            countdown = -1;
        });
    }

    /** Viewer plugins register the code that re-applies their hiding. */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** The hiding state for the local player right now. */
    public static ViewerHiding current() {
        return ViewerHiding.of(RestrictionManager.client(), ViewerState.active() ? ClientStageCache.get() : null);
    }

    /** Called by a viewer that just rebuilt itself from the live state (e.g. JEI runtime reload). */
    public static void markApplied() {
        applied = current();
        appliedMode = ServerConfig.recipeHiding();
        appliedMobs = currentMobs();
        countdown = -1;
    }

    private static Set<ResourceLocation> currentMobs() {
        return ViewerState.hidesMobEntries() ? MobDiscoveries.get() : null;
    }

    private static void tick() {
        if (Minecraft.getInstance().player == null) return;
        ViewerHiding now = current();
        ServerConfig.RecipeHiding mode = ServerConfig.recipeHiding();
        boolean modeChanged = mode != appliedMode && !(now.active().isEmpty() && applied.active().isEmpty());
        Set<ResourceLocation> mobs = currentMobs();
        if (!modeChanged && !now.changedFrom(applied) && java.util.Objects.equals(mobs, appliedMobs)) {
            applied = now;
            countdown = -1;
            return;
        }
        if (countdown < 0) countdown = DEBOUNCE_TICKS;
        if (countdown-- > 0) return;
        applied = now;
        appliedMode = mode;
        appliedMobs = mobs;
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                StageLock.LOGGER.error("Recipe viewer refresh failed", e);
            }
        }
    }
}
