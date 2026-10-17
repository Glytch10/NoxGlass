# Changelog

## [1.12.0] - 2026-10-04

### Added

- Added support for 26.1.2 Fabric, 26.1.2 NeoForge, 26.3 Fabric and 26.3 NeoForge ([#227](https://github.com/IMB11-Mods/JavaEditionMods/pull/227))

## [1.11.0] - 2025-10-07

### Added

- Support for Minecraft 1.21.10.

## [1.10.0] - 2025-10-04

### Added

- Support for Minecraft 1.21.9 (by [cassiancc](https://github.com/cassiancc) in [#99](https://github.com/IMB11/Fog/pull/99)).

## [1.9.3] - 2025-07-27

### Added

- Support for Minecraft 1.21.8.

## [1.9.2] - 2025-07-11

### Changed

- Updated Russian (`ru_ru`) translations (by [mpustovoi](https://github.com/mpustovoi) in [#91](https://github.com/IMB11/Fog/pull/91))

### Fixed

- A crash on startup on NeoForge due to an invalid Mixin configuration

## [1.9.1] - 2025-07-10

### Fixed

- A dependency error on startup due to the M.R.U dependency being specified incorrectly on Minecraft 1.21.7
- A crash on startup due to a mixins not remapping correctly
- An incorrect translation key for the `enableMod` configuration option

## [1.9.0] - 2025-07-09

### Added

- Argentine Spanish (`es_ar`) translation (by [Texaliuz](https://github.com/Texaliuz) in [#81](https://github.com/IMB11/Fog/pull/81))
- Support for Minecraft 1.21.6 and 1.21.7

### Changed

- Made fog brighter during rainstorms and darker during thunderstorms
- Replaced the `disableRaininessEffect` configuration option with `rainFogMultiplier`
- Replaced the `disableUndergroundFogMultiplier` configuration option with `undergroundFogMultiplier`
- Renamed and inverted various configuration options
- Improved the configuration screen
  - Condensed all configuration options into 3 categories: general, transition speeds and compatibility

### Fixed

- The `/fog debug` command not working due to the hexadecimal color in some cases
- The sunrise/sunset fog colors being applied when the darkness effect is active

## [1.8.0] - 2025-04-17

### Added

- Support for Minecraft 1.21.5.

## [1.7.0] - 2025-04-10

### Added

- Added an option to disable the mod automatically when Iris shaders are active. This is enabled by default.
- Added an option to disable the logic where the fog color would match the sky color when the player goes over a certain altitude. ("Enable High Altitude Fog")
- Added an option to prioritize Polytone fog colors over Fog's fog definitions. ("Prioritize Polytone Fog Colors")

### Changed

- Reworked sunset/sunrise blending logic, now sunsets and sunrises work spectacularly with the Fog mod.

## [1.6.4] - 2025-03-02

### Fixed

- Fixed an incorrect dependency declaration for Architectury API on NeoForge

## [1.6.3] - 2025-03-01

### Fixed

- Fixed an incorrect dependency declaration on NeoForge

## [1.6.2] - 2025-02-23

### Changed

- Updated the Simplified Chinese (`zh_cn`) language files (by [Chiloven945](https://github.com/Chiloven945) in https://github.com/IMB11/Fog/pull/64)

### Fixed

- Fixed the Overworld's default fog colors
  - Nighttime fog is now pitch black again by default (in most biomes) in the Overworld
- Fixed M.R.U not being specified as a required dependency on NeoForge
- Fixed compatibility with Architectury API `v15.0.2` (on Minecraft 1.21.4)
  - Architectury API `v15.0.1` (on Minecraft 1.21.4) has been marked as incompatible

## [1.6.1] - 2024-12-27

### Changed

- Updated Russian (`ru_ru`) translations.

### Fixed

- Fixed dependency issues for Minecraft 1.21.3.

## [1.6.0] - 2024-12-27

### Added

- Support for Minecraft 1.21.4.
- Sunrise and sunset enhancements for more vibrant transitions between day and night.
- Configurable moon phase modifiers that darken biome fog at night.

### Changed

- Reorganized the configuration screen into categories.
- Replaced the disabled dimensions list with a disabled biomes list. Existing configurations need updating.
- Default fog colors are now extracted from `DimensionEffects`/`DimensionSpecialEffects` to improve compatibility with mods such as [The Bumblezone](https://modrinth.com/mod/the-bumblezone-fabric) and [The Aether](https://modrinth.com/mod/aether).
- Disabled cloud whitening by default to improve clouds at night and during sunrise and sunset. It can still be enabled in the configuration screen.

## [1.5.3] - 2024-12-06

### Changed

- Updated YACL to `v3.6.2`

## [1.5.2] - 2024-10-20

### Changed

- Updated the Russian translations (by [mpustovoi](https://github.com/mpustovoi) in #44)

## [1.5.1] - 2024-10-20

### Changed

- Updated the Traditional Chinese translations (by [yichifauzi](https://github.com/yichifauzi) in #43)

## [1.5.0] - 2024-10-20

### Added

- Added Simplified Chinese translations, thank you @Chiloven945!
- Added a keybinding to toggle the fog modifications, it is unbinded by default, you must set it before you use it. @IMB11
- Added configuration options for various transitions, @IMB11
  - e.g. fog colours, start/end multipliers, etc.
- Added a dimension disable list to the configuration screen, @Steveplays28
  - Replaced the Nether dimension toggle

### Changed

- Custom fog definitions must now be supplied through resource packs instead of the configuration folder. Default definitions must also be overridden through resource packs.
- Made the badlands, beach and default biome colours darker during nighttime, @Steveplays28
  - The default nighttime fog colour for most biomes has been changed to pitch black

### Removed

- Removed the (un)packing system for the built-in resource pack, @IMB11
  - Should fix a lot of crashes and compatibility issues
- Removed haze from the debug command and the language files as it no longer exists, @Steveplays28

### Fixed

- Fixed a crash when other mods mess around with `RenderLayer`s, @Steveplays28
- Fixed cloud whitening (when sodium is not installed) for 1.21+, @Steveplays28
- Fixed the `raininess` effect breaking when standing under trees/walking over non-full blocks, @Steveplays28

## [1.4.1] - 2024-09-10

### Changed

- Updated the Russian translation (`ru_ru`) by @mpustovoi in [#26](<https://github.com/IMB11/Fog/pull/26>)

### Fixed

- Compatibility with Sodium `v0.6.0`
  - By extension, all resource reload issues with other mods (e.x. Frostiful and Guarding) should be fixed as well
- Resource reload fixes were developed with help from cortex, XFactHD, Pepper and others.

## [1.4.0] - 2024-08-31

### Added

- Added a warning that shows when Fog updates and the new update modifies some of the default biome colours - it will recommend you to backup any changes within the `.minecraft/config/fog/fog_definitions` folder and then delete it so the default biome colours can be reset and updated. We will look into a diff-like system for this at a later date.

### Changed

- Updated biome colours for: swamp biomes, jungle biomes, badlands/mesa biomes, sandy biomes (desert/beach), the end biomes and icy aquatic biomes. They should be much less bright and more consistent with the default vanilla biome colours now.
- Required YACL 3.5.0, Architectury API and MRU.

### Fixed

- Fixed various issues with underground fog, there will be less of a flicker when coming out of caves, and the underground multiplier will no longer rarely kick in when you enter buildings.
- Fixed issue where fog goes black when it's raining.
- Potentially fix some crash issues on 1.20.1 forge with YACL 3.5.0 - let us know if you're still encountering crashes here.

## [1.3.1] - 2024-08-15

### Changed

- Updated the required MRU version to 1.0.0.
- Moved shared functionality, including external resource pack handling, into MRU for reuse by other mods.

## [1.3.0] - 2024-08-14

### Added

- Added support for 1.21.1.
- Added new point-based calculations to detect if a player is underground. This should fix issues where being inside a building tricks Fog into thinking you're underground.

### Changed

- The default day and night colours have been changed, night is now much darker and closer resembles the vanilla night fog colours. Vice versa for the day colours.
- The colours for the `#c:is_swamp` fog definition have been modified to account for these new default colours. Delete the `.minecraft/config/fog/fog_definitions/assets/c/fog_definitions/tag/biome/is_swamp.json` file to get the updated file.
- When in caves, the fog start will be much closer, fixing issues where there is a "pane" of colour blocking the view. It also massively improves cave fog in general.

### Removed

- Removed "Haze" system in favour of a single blend between day and night colours.

### Fixed

- Fixed issue where resource packs could not override Fog's default definitions due to the fact that Fog's resource pack was being loaded at the top regardless of the ordering on the Select Resource Pack screen.

## [1.2.0] - 2024-08-09

### Added

- Turkish (`tr_tr`) translations (by @digi2303 in [#15](https://github.com/IMB11/Fog/pull/15)).

## [1.1.0] - 2024-08-07

### Added

- Ukrainian (`uk_ua`) translations (by @Tarteroycc in [#12](https://github.com/IMB11/Fog/pull/12)).

## [1.0.5] - 2024-08-04

### Fixed

- Rewrote external resource pack loading to fix loading issues with packs in the configuration directory.

## [1.0.4] - 2024-08-04

### Added

- Added Russian translations, thanks @mpustovoi

### Fixed

- Fixed `FileSystem` crashes on NeoForge and Forge.

## [1.0.3] - 2024-08-03

### Fixed

- Fixed a startup crash caused by an incorrect check for asset namespace subdirectories.

## [1.0.2] - 2024-08-03

### Fixed

- Fixed external resource packs not being unpacked from the jar into `config/fog/fog_definitions`.
- Fixed resource packs failing to load in some scenarios.
- Fixed intermittent resource unpacker crashes affecting Windows users, with debugging help from @arcyteo000.

## [1.0.1] - 2024-08-03

### Added

- Simplified Chinese translations.

### Fixed

- Fixed the resource pack format.

## [1.0.0] - 2024-08-03

### Added

- Initial release of Fog.

[1.11.0]: https://github.com/IMB11/Fog/releases/tag/1.11.0
[1.10.0]: https://github.com/IMB11/Fog/releases/tag/1.10.0
[1.9.3]: https://github.com/IMB11/Fog/releases/tag/1.9.3
[1.9.2]: https://github.com/IMB11/Fog/releases/tag/1.9.2
[1.9.1]: https://github.com/IMB11/Fog/releases/tag/1.9.1
[1.9.0]: https://github.com/IMB11/Fog/releases/tag/1.9.0
[1.8.0]: https://github.com/IMB11/Fog/releases/tag/1.8.0
[1.7.0]: https://github.com/IMB11/Fog/releases/tag/1.7.0
[1.6.4]: https://github.com/IMB11/Fog/releases/tag/1.6.4
[1.6.3]: https://github.com/IMB11/Fog/releases/tag/1.6.3
[1.6.2]: https://github.com/IMB11/Fog/releases/tag/1.6.2
[1.6.1]: https://github.com/IMB11/Fog/releases/tag/1.6.1
[1.6.0]: https://github.com/IMB11/Fog/releases/tag/1.6.0
[1.5.3]: https://github.com/IMB11/Fog/releases/tag/1.5.3
[1.5.2]: https://github.com/IMB11/Fog/releases/tag/1.5.2
[1.5.1]: https://github.com/IMB11/Fog/releases/tag/1.5.1
[1.5.0]: https://github.com/IMB11/Fog/releases/tag/1.5.0
[1.4.1]: https://github.com/IMB11/Fog/releases/tag/1.4.1
[1.4.0]: https://github.com/IMB11/Fog/releases/tag/1.4.0
[1.3.1]: https://github.com/IMB11/Fog/releases/tag/1.3.1
[1.3.0]: https://github.com/IMB11/Fog/releases/tag/1.3.0
[1.2.0]: https://github.com/IMB11/Fog/releases/tag/1.2.0
[1.1.0]: https://github.com/IMB11/Fog/releases/tag/1.1.0
[1.0.5]: https://github.com/IMB11/Fog/releases/tag/1.0.5
[1.0.4]: https://github.com/IMB11/Fog/releases/tag/1.0.4
[1.0.3]: https://github.com/IMB11/Fog/releases/tag/1.0.3
[1.0.2]: https://github.com/IMB11/Fog/releases/tag/1.0.2
[1.0.1]: https://github.com/IMB11/Fog/releases/tag/1.0.1
[1.0.0]: https://github.com/IMB11/Fog/releases/tag/1.0.0
