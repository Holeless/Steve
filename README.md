# Steve Everything (NeoForge 1.21.1)

Every mob becomes Steve. Client-side only, so it works on any server and the server needs nothing installed.

## Build
1. Install Java 21.
2. Get the Gradle wrapper (this project doesn't ship the binary jar). Either
   - run `gradle wrapper --gradle-version 8.10.2` if you have Gradle installed, or
   - copy `gradlew`, `gradlew.bat` and the `gradle/` folder from the official NeoForge 1.21.1 MDK into this folder.
3. Run `./gradlew build` (Windows: `gradlew.bat build`).
4. Your mod is `build/libs/steveeverything-1.0.0.jar`. Drop it in `.minecraft/mods`.
   To test in a dev client instead: `./gradlew runClient`.

## How the algorithm works
When a mob is about to be drawn for the first time, the mod builds a Steve version of that mob's texture:

1. **Read the model.** It walks the mob's model parts and records every cuboid and where its six faces sit on the texture
   (done with a dummy `VertexConsumer`, so no access transformers or mixins are needed).
2. **Pick a Steve body part per cuboid.** From the part's name (`head` -> head, `right_hind_leg` -> leg, `wing`/`fin` -> arm,
   `body` -> torso, `beak`/`horn`/`udder`/`tail`... -> bare skin, `hat` -> Steve's empty second layer).
   Unnamed parts are judged by position and shape, and small cubes inside a bigger part (horns, udders) become skin.
3. **Copy matching faces.** Each face rectangle (top, bottom, right, front, left, back) gets the same face of that Steve
   body part, stretched with nearest-neighbour sampling so it stays pixel-crisp.
4. The result is registered as a dynamic texture under the mob's original texture path, so every renderer picks it up.

Textures are generated once per texture and regenerated on F3+T.

## Changing the skin
Replace `assets/steveeverything/textures/steve.png` (inside the jar, or from a resource pack at the same path).
Any classic-layout skin works (64x32 or 64x64, HD multiples too).

## Known limits
- Extra render layers (sheep wool, villager outfits, armor, creeper charge, glowing eyes) keep their vanilla look.
- The Ender Dragon isn't a living-entity renderer, so it is left vanilla. Players are never changed.
- Mobs whose texture is generated at runtime (not a file) are skipped.
