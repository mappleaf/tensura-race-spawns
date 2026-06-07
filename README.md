# Tensura Race Spawn Settings
(Tensura Race Spawns or just TRSS for short)

A small addon for **Tensura Reincarnated** that lets you configure custom spawn locations for each race.
The main idea is simple: different races can start in different dimensions, biomes, or structures. This also works with races added by other addons, as long as you know their registry id.

Configuration is stored in:
```text
config/tensura/race_spawns.toml
```

Example:
```toml
[settings]
    spawnRadius=6400
    useAsyncLocator=true

[Races]
    [Races.slime]
        spawnDimension="minecraft:overworld"
        spawnBiome=["#minecraft:is_overworld"]
        spawnStructure=[]
        onlyInitial=true
        assignedToPlayer=false

    [Races."exampleaddon:custom_race"]
        spawnDimension="minecraft:overworld"
        spawnBiome=["minecraft:swamp", "#is_taiga"]
        spawnStructure=["#minecraft:village", "minecraft:mineshaft"]
        onlyInitial=false
        assignedToPlayer=true
```


## What it does
The addon can:
- set a race-specific initial spawn point;
- search for configured biomes (or tags);
- search for configured structures (or tags);
- optionally use **Async Locator Refined** for async biome/structure search;
- assign found spawn locations to specific players if configured;

Also you can hot-reload your config in-game:
```mcfunction
/tensuraracespawns reload
```


## Config behavior
Empty values mean “ignore this option and use the default spawn logic”.

```toml
spawnDimension=""
spawnBiome=[]
spawnStructure=[]
```

If all three spawn fields are empty, the player uses the normal world spawn.
`onlyInitial=true` means the found point is used like a normal `/spawnpoint` or bed spawn.
`onlyInitial=false` makes the configured spawn behave more like a personal world spawn for that player.
`assignedToPlayer=true` prevents the same configured biome/structure location from being reused for another player.


## Optional dependency
- [Async Locator Refined](https://modrinth.com/project/LUIHK4LD)
It is optional, but highly recommended. The addon should still work without it.


## Project building
To build project from source you need manually put Tensura mod jar in `libs/` folder, otherwise the build will fail.
Required version can be seen in `gradle.properties`.
Then just run `gradlew build`.


## Maintenance note
This mod was made for a specific need, and I do not currently have a strong desire to actively maintain it in long-term.
Pull requests are welcome if someone wants to fix bugs, improve compatibility, clean up the code, or extend the config system.
If someone more motivated wants to actively maintain this idea, I would also be happy to see an active fork of the project.


## AI notice
The project was almost entirely made using ai because I don't give a fuck. I reviewed every change and read every line though,
so project quality isn't that terrible.
