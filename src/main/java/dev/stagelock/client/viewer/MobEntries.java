package dev.stagelock.client.viewer;

import dev.stagelock.StageLock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the mob a recipe viewer entry is the loot page of, for the mods that add such pages.
 * <p>
 * No viewer has a notion of "this entry belongs to a mob": each loot mod keeps the entity type somewhere
 * in its own entry class. Those classes are internal to the loot mods, so they are read reflectively and
 * a mod that changes them only stops having its pages hidden (with one warning in the log).
 */
public final class MobEntries {

    /** One hop from an object to the next: a field or no-argument method of the class named {@code owner}. */
    public record Step(String owner, String member, boolean field) {
    }

    /**
     * How one kind of entry leads to its mob. Each step applies only if the value is an instance of the
     * step's owner; otherwise the entry is not a mob's loot page. The last value is an entity type or an entity.
     */
    public record Adapter(String modId, String viewer, List<Step> steps) {
    }

    public static final List<Adapter> ADAPTERS = List.of(
            // Just Enough Resources: the JEI recipe is the MobEntry itself.
            new Adapter("jeresources", "JEI", List.of(method("jeresources.entry.MobEntry", "getEntity"))),
            // EMI Loot.
            new Adapter("emi_loot", "EMI", List.of(field("fzzyhmstrs.emi_loot.emi.MobLootRecipe", "type"))),
            // Advanced Loot Info wraps one shared loot record differently for each viewer.
            new Adapter("ali", "JEI", List.of(method("com.yanny.ali.jei.compatibility.jei.RecipeHolder", "type"),
                    method("com.yanny.ali.compatibility.common.EntityLootType", "entityType"))),
            new Adapter("ali", "EMI", List.of(field("com.yanny.ali.emi.compatibility.emi.EmiEntityLoot", "entityType"))),
            new Adapter("ali", "REI", List.of(method("com.yanny.ali.rei.compatibility.rei.ReiEntityDisplay", "getEntityType"))));

    private static final ClassValue<Optional<Adapter>> BY_CLASS = new ClassValue<>() {
        @Override
        protected Optional<Adapter> computeValue(Class<?> type) {
            for (Adapter adapter : ADAPTERS) {
                if (owner(type, adapter.steps().getFirst().owner()) != null) return Optional.of(adapter);
            }
            return Optional.empty();
        }
    };
    private static final Map<Step, AccessibleObject> MEMBERS = new ConcurrentHashMap<>();
    private static final Set<Adapter> BROKEN = ConcurrentHashMap.newKeySet();
    /** Entries never change mob, and JER builds a whole entity to answer. */
    private static final Map<Object, Optional<EntityType<?>>> RESOLVED = Collections.synchronizedMap(new WeakHashMap<>());
    @Nullable
    private static Boolean providerLoaded;

    private MobEntries() {
    }

    /** Whether any mod is installed whose mob loot pages can be recognised. */
    public static boolean providerLoaded() {
        Boolean loaded = providerLoaded;
        if (loaded == null) {
            loaded = ADAPTERS.stream().anyMatch(adapter -> ModList.get().isLoaded(adapter.modId()));
            providerLoaded = loaded;
        }
        return loaded;
    }

    /** The mob whose loot this viewer entry shows, or null if it is not a mob's loot page. */
    @Nullable
    public static EntityType<?> mobOf(Object entry) {
        Adapter adapter = BY_CLASS.get(entry.getClass()).orElse(null);
        if (adapter == null || BROKEN.contains(adapter)) return null;
        Optional<EntityType<?>> known = RESOLVED.get(entry);
        if (known == null) {
            known = Optional.ofNullable(resolve(adapter, entry));
            RESOLVED.put(entry, known);
        }
        return known.orElse(null);
    }

    @Nullable
    private static EntityType<?> resolve(Adapter adapter, Object entry) {
        Object value = entry;
        try {
            for (Step step : adapter.steps()) {
                if (value == null) return null;
                Class<?> owner = owner(value.getClass(), step.owner());
                if (owner == null) return null;
                AccessibleObject member = MEMBERS.get(step);
                if (member == null) {
                    member = step.field() ? owner.getDeclaredField(step.member()) : owner.getDeclaredMethod(step.member());
                    member.setAccessible(true);
                    MEMBERS.put(step, member);
                }
                value = member instanceof Field f ? f.get(value) : ((Method) member).invoke(value);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (BROKEN.add(adapter)) {
                StageLock.LOGGER.warn("Could not read the mob of {} loot entries in {}; they are not hidden until their mob is killed",
                        adapter.modId(), adapter.viewer(), e);
            }
            return null;
        }
        return switch (value) {
            case EntityType<?> type -> type;
            case Entity entity -> entity.getType();
            case null, default -> null;
        };
    }

    /** {@code type} or the superclass of it that is named {@code name}. */
    @Nullable
    private static Class<?> owner(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }

    private static Step field(String owner, String name) {
        return new Step(owner, name, true);
    }

    private static Step method(String owner, String name) {
        return new Step(owner, name, false);
    }
}
