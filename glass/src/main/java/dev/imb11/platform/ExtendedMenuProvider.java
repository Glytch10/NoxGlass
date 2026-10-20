//? if fabric {
package dev.imb11.platform;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public interface ExtendedMenuProvider<T> extends ExtendedScreenHandlerFactory<T> {
    StreamCodec<? super RegistryFriendlyByteBuf, T> getScreenOpeningCodec();
}
//?} else {
/*package dev.imb11.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;

public interface ExtendedMenuProvider<T> extends MenuProvider {
    T getScreenOpeningData(ServerPlayer player);

    StreamCodec<? super RegistryFriendlyByteBuf, T> getScreenOpeningCodec();
}
*///?}
