package com.wsteam.wandscape.engine.service.client;

import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ClientSoundHelper {
    private ClientSoundHelper() {}

    public static void playUI(DeferredHolder<SoundEvent, SoundEvent> sound, float pitch) {
        if (sound == null || !sound.isBound()) return;
        net.minecraft.client.Minecraft.getInstance().getSoundManager()
                .play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound.get(), pitch));
    }
}
