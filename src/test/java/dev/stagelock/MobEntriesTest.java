package dev.stagelock;

import dev.stagelock.client.viewer.MobEntries;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MobEntries} reads other mods' internal classes by name. This checks every name against the
 * jars of the versions in {@code gradle.properties}, so a loot mod update that moves something fails
 * here instead of quietly leaving its pages visible in game.
 */
class MobEntriesTest {

    private static List<ZipFile> jars() throws IOException {
        String path = System.getProperty("stagelock.lootMods", "");
        assertFalse(path.isBlank(), "the loot mod jars were not passed to the test");
        List<ZipFile> jars = new ArrayList<>();
        for (String jar : path.split(File.pathSeparator)) jars.add(new ZipFile(jar));
        return jars;
    }

    /** Whether the class declares the member: a field of any type, or a method without parameters. */
    private static boolean declares(List<ZipFile> jars, MobEntries.Step step) throws IOException {
        String file = step.owner().replace('.', '/') + ".class";
        for (ZipFile jar : jars) {
            ZipEntry entry = jar.getEntry(file);
            if (entry == null) continue;
            boolean[] found = {false};
            try (InputStream in = jar.getInputStream(entry)) {
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        if (step.field() && name.equals(step.member())) found[0] = true;
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!step.field() && name.equals(step.member()) && descriptor.startsWith("()")) found[0] = true;
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            return found[0];
        }
        return fail(step.owner() + " is in none of the loot mod jars");
    }

    @Test
    void everyAdapterMatchesTheRealMods() throws IOException {
        List<ZipFile> jars = jars();
        try {
            for (MobEntries.Adapter adapter : MobEntries.ADAPTERS) {
                for (MobEntries.Step step : adapter.steps()) {
                    assertTrue(declares(jars, step), adapter.modId() + " (" + adapter.viewer() + "): "
                            + step.owner() + " has no " + (step.field() ? "field " : "method ") + step.member());
                }
            }
        } finally {
            for (ZipFile jar : jars) jar.close();
        }
    }
}
