package dev.example.mapi.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.json.JsonReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicApiCoverageTest {
    @Test
    void publicMemberInventoryCannotSilentlyOmitNewMembersOrTypes() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("e2e/api/java-members.json"))) root = root.getParent();
        var raw = (Map<?, ?>) JsonReader.parse(Files.readString(root.resolve("e2e/api/java-members.json")));
        Set<String> actual = new LinkedHashSet<>();
        var types = new java.util.ArrayList<Class<?>>(List.of(Mapi.class, MapiApi.class, MapiService.class,
                MapiServices.class, PlatformType.class, ServerStatusSnapshot.class));
        for (int index = 0; index < types.size(); index++) {
            Class<?> type = types.get(index);
            for (Class<?> nested : type.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers())) types.add(nested);
            }
            for (var method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    assertTrue(actual.add(type.getSimpleName() + "." + method.getName()),
                            "overloaded public methods need distinct signature coverage keys");
                }
            }
            for (var constructor : type.getConstructors()) {
                assertTrue(actual.add(type.getSimpleName() + ".<init>"),
                        "overloaded public constructors need distinct signature coverage keys");
            }
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers()) && !field.isSynthetic()) {
                    assertTrue(actual.add(type.getSimpleName() + "." + field.getName()));
                }
            }
        }
        assertEquals(raw.keySet(), actual, "declare explicit JVM/live coverage for every public member");
        try (var sources = Files.list(root.resolve("common/src/main/java/dev/example/mapi/api"))) {
            assertEquals(Set.of("Mapi.java", "MapiApi.java", "MapiService.java", "MapiServices.java",
                    "PlatformType.java", "ServerStatusSnapshot.java", "package-info.java"),
                    sources.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
                    "new public API types need inventory and behavior tests");
        }
    }

    @Test
    void bootstrapContractRunsInAnIsolatedClassLoader() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[] {
                MapiApi.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> facade = loader.loadClass(MapiApi.class.getName());
            Class<?> api = loader.loadClass(Mapi.class.getName());
            assertTrue(((java.util.Optional<?>) facade.getMethod("get").invoke(null)).isEmpty());
            assertTrue(assertThrows(InvocationTargetException.class,
                    () -> facade.getMethod("require").invoke(null)).getCause() instanceof IllegalStateException);
            assertTrue(assertThrows(InvocationTargetException.class,
                    () -> facade.getMethod("bind", api).invoke(null, new Object[] {null})).getCause()
                    instanceof IllegalArgumentException);
            Object instance = Proxy.newProxyInstance(loader, new Class<?>[] {api}, (proxy, method, args) -> null);
            facade.getMethod("bind", api).invoke(null, instance);
            facade.getMethod("bind", api).invoke(null, instance);
            assertTrue(facade.getMethod("require").invoke(null) == instance);
            Object second = Proxy.newProxyInstance(loader, new Class<?>[] {api}, (proxy, method, args) -> null);
            assertTrue(assertThrows(InvocationTargetException.class,
                    () -> facade.getMethod("bind", api).invoke(null, second)).getCause() instanceof IllegalStateException);
        }
    }

    @Test
    void snapshotValueAndEnumMembersHaveExplicitBehaviorCoverage() {
        var normalized = new ServerStatusSnapshot(-1, -2, -3, -4, -5, Double.NaN, null);
        assertEquals(new ServerStatusSnapshot(0, 0, 0, 0, 0, 0, ""), normalized);
        assertEquals(new ServerStatusSnapshot(0, 0, 0, 0, 0, 0, "").hashCode(), normalized.hashCode());
        assertNotNull(normalized.toString());
        assertEquals(0, normalized.uptimeMs());
        assertFalse(normalized.equals(null));
        assertEquals(List.of(PlatformType.FABRIC, PlatformType.NEOFORGE), List.of(PlatformType.values()));
        assertEquals(PlatformType.FABRIC, PlatformType.valueOf("FABRIC"));
        assertEquals("fabric", PlatformType.FABRIC.id());
        assertEquals("neoforge", PlatformType.NEOFORGE.id());
    }
}
