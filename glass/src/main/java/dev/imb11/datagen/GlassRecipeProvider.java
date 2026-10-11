//? fabric {
package dev.imb11.datagen;

import dev.imb11.items.GItems;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricRecipeProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.concurrent.CompletableFuture;

public class GlassRecipeProvider extends FabricRecipeProvider {
    public GlassRecipeProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registriesFuture) {
        super(output, registriesFuture);
    }

    @Override
    public String getName() {
        return "GLASS recipes";
    }

    @Override
    protected RecipeProvider createRecipeProvider(HolderLookup.Provider registries, RecipeOutput output) {
        return new RecipeProvider(registries, output) {
            @Override
            public void buildRecipes() {
        shapeless(RecipeCategory.BUILDING_BLOCKS, GItems.REDSTONE_INFUSED_SAND)
                .requires(Items.REDSTONE)
                .requires(Items.SAND)
                .unlockedBy("has_ingredient", has(Items.REDSTONE))
                .save(output);

        SimpleCookingRecipeBuilder.smelting(Ingredient.of(GItems.REDSTONE_INFUSED_SAND), RecipeCategory.BUILDING_BLOCKS, CookingBookCategory.BLOCKS, GItems.POWERABLE_GLASS, 0.1F, 200)
                .unlockedBy("has_ingredient", has(GItems.REDSTONE_INFUSED_SAND))
                .save(output);

        shaped(RecipeCategory.REDSTONE, GItems.PROJECTOR)
                .pattern("GQG")
                .pattern("GRG")
                .pattern("OOO")
                .define('G', GItems.POWERABLE_GLASS)
                .define('Q', Items.QUARTZ)
                .define('R', Items.REDSTONE)
                .define('O', Items.OBSIDIAN)
                .unlockedBy("has_ingredient", has(Items.QUARTZ))
                .save(output);

        shaped(RecipeCategory.REDSTONE, GItems.TERMINAL)
                .pattern("OBO")
                .pattern("OEO")
                .pattern("OOO")
                .define('O', Items.OBSIDIAN)
                .define('B', Items.OBSERVER)
                .define('E', Items.ENDER_EYE)
                .unlockedBy("has_ingredient", has(Items.OBSERVER))
                .save(output);
            }
        };
    }
}
//?}
