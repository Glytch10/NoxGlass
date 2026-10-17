//? if fabric {
package dev.imb11.platform;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

public final class Platform {
    private Platform() {
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    public static <T> void openMenu(Player player, ExtendedMenuProvider<T> provider) {
        if (provider != null) {
            player.openMenu(provider);
        }
    }

    public static <T extends AbstractContainerMenu, D> MenuType<T> menuType(MenuFactory<T, D> factory, StreamCodec<? super RegistryFriendlyByteBuf, D> codec) {
        return new ExtendedScreenHandlerType<>(factory::create, codec);
    }
}
//?} else {
/*package dev.imb11.platform;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

public final class Platform {
    private Platform() {
    }

    public static boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    public static <T> void openMenu(Player player, ExtendedMenuProvider<T> provider) {
        if (player instanceof ServerPlayer serverPlayer && provider != null) {
            serverPlayer.openMenu(provider, buffer -> provider.getScreenOpeningCodec().encode(
                    new RegistryFriendlyByteBuf(buffer, serverPlayer.registryAccess()),
                    provider.getScreenOpeningData(serverPlayer)
            ));
        }
    }

    public static <T extends AbstractContainerMenu, D> MenuType<T> menuType(MenuFactory<T, D> factory, StreamCodec<? super RegistryFriendlyByteBuf, D> codec) {
        return IMenuTypeExtension.create((id, inventory, buffer) -> factory.create(id, inventory,
                codec.decode(new RegistryFriendlyByteBuf(buffer, inventory.player.registryAccess()))));
    }
}
*///?}
