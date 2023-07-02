package de.geolykt.starloader.lwjgl3ify;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.lwjgl.glfw.GLFW;

import net.minestom.server.extras.selfmodification.MinestomRootClassLoader;

public class LWJGL3ify {

    public static void main(String[] args) throws Throwable {
        GLFW.glfwGetCurrentContext();
        String forwardTarget = System.getProperty("de.geolykt.starloader.lwjgl3ify.forwardTo");
        if (forwardTarget == null) {
            throw new IllegalStateException("The System property \"de.geolykt.starloader.lwjgl3ify.forwardTo\" is not set.");
        }
        String transformSource = System.getProperty("de.geolykt.starloader.lwjgl3ify.transformFrom");
        if (transformSource == null) {
            throw new IllegalStateException("The System property \"de.geolykt.starloader.lwjgl3ify.transformFrom\" is not set.");
        }
        String transformTarget = System.getProperty("de.geolykt.starloader.lwjgl3ify.transformTo");
        if (transformTarget == null) {
            throw new IllegalStateException("The System property \"de.geolykt.starloader.lwjgl3ify.transformTo\" is not set.");
        }
        if (transformSource.equals(transformTarget)) {
            throw new IllegalStateException("The transform source and transform targets may not match.");
        }

        LWJGL3Transformer.invoke(Paths.get(transformSource), Paths.get(transformTarget));

        String libDir = System.getProperty("de.geolykt.starloader.lwjgl3ify.extraLibraryDirectory");
        if (libDir != null) {
            for (Path p : Files.list(Paths.get(libDir)).toArray(Path[]::new)) {
                if (p.getFileName().toString().endsWith(".jar")) {
                    MinestomRootClassLoader.getInstance().addURL(p.toUri().toURL());
                }
            }
        }
        if (Boolean.getBoolean("de.geolykt.starloader.lwjgl3ify.appendClasspath")) {
            MinestomRootClassLoader.getInstance().addURL(Paths.get(transformTarget).toAbsolutePath().toUri().toURL());
        }

        try {
            MethodHandle handle = MethodHandles.publicLookup().findStatic(Class.forName(forwardTarget), "main", MethodType.methodType(void.class, String[].class));
            handle.invokeExact(args);
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException e) {
            throw new IllegalStateException("Unable to forward to forward target (\"" + forwardTarget + "\")", e);
        }

        if (Boolean.getBoolean("de.geolykt.starloader.lwjgl3ify.killOnReturn")) {
            System.exit(0);
        }
    }
}
