package io.github.turniplabs.btaprovider.services;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.entrypoint.EntrypointUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Hooks {
    public static final String INTERNAL_NAME = Hooks.class.getName().replace('.', '/');

    public static void init() {
        Path runDir = Paths.get(".");

        QuiltLoaderImpl.INSTANCE.prepareModInit(runDir, QuiltLoaderImpl.INSTANCE.getGameInstance());
        EntrypointUtils.invoke("main", ModInitializer.class, ModInitializer::onInitialize);
        if (QuiltLoaderImpl.INSTANCE.getEnvironmentType() == EnvType.CLIENT) {
            EntrypointUtils.invoke("client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
        } else {
            EntrypointUtils.invoke("server", DedicatedServerModInitializer.class, DedicatedServerModInitializer::onInitializeServer);
        }
    }
}
