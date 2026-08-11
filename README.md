# Rat King Recon

An idle Android game that runs on your feet. Your phone's step counter is the
only resource in the game: walking earns EXP toward the next rat, and the same
steps roll for Rustbot encounters, feed timed contracts, and eventually put a
boss on your trail.

Kotlin, XML views, Room. Minimum Android 7.0 (API 24).

## The loop

Steps are read by a foreground service and folded into the save by
`GameEngine.onSteps`, which is the only thing in the app allowed to change
progress. One step is one EXP; levelling costs `50 × level`, and each level-up
hatches a rat into the Ledger.

The same batch of steps independently rolls for two other things:

- **Rustbot encounters**, roughly one per 400 steps, announced by notification
  and settled either by hand or straight from the lock screen.
- **Boss Rustbots**, roughly one per 2,500 steps while you are inside a boss's
  level band. A boss is *banked* rather than raised — the walk records only that
  one is owed, and the fight is built the next time you open the app, so it
  cannot be auto-resolved from a pocket.

## What is in it

| System | Summary |
|---|---|
| Hatching | 32 species, shiny rolls, Power and Toughness 1-5 |
| The Rat Ledger | The collection, with sorting, filtering and the Fusion Pot |
| Fusion Pot | Burns 5 Scrap and your two weakest rats to mint a stronger mutant |
| Combat | Turn-based, Attack / Defend / Special, fully deterministic |
| Boss Rustbots | Five tiers from Level 10 to 50+, re-fightable, badge on first win |
| Contract Board | Timed step bounties for Scrap |
| Ledger Tasks | Timed roster jobs with alarm-driven completion |
| Shop | Five sections: consumables, combat buffs, expeditions, cosmetics |
| Achievements | Boss badges plus step, roster and hatching milestones |
| Save transfer | Export and import as JSON over the Storage Access Framework |

## Building

```
./gradlew assembleDebug        # APK to app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # JVM tests, no device needed
```

The unit tests cover the game rules rather than the UI — step accounting, boss
tiering and balance, the Shop's effects, the Fusion Pot's rarity engine and the
milestone latching. They run on the JVM against fakes, so they need no emulator.

## How the code is laid out

Game rules live in plain objects with no Android dependencies where possible, so
they can be tested off-device. Activities render and dispatch; they do not decide
anything.

| File | Holds |
|---|---|
| `GameEngine.kt` | Every step-driven rule. The only writer of progress. |
| `Battle.kt` | The combat simulator. No Android imports, no randomness. |
| `Boss.kt` | Boss tiers, banking, badges. |
| `Fusion.kt` | The Fusion Pot's rarity engine. |
| `Shop.kt` / `ShopEffects.kt` | The catalogue, and what owning something means. |
| `Achievements.kt` | Milestones and their latching. |
| `rat.kt` | The species roster, and rarity lookups over it. |
| `StepTrackerService.kt` | Owns the sensor; the only caller of `onSteps`. |
| `SaveTransfer.kt` | Export and import. |

Two rules worth knowing before changing anything:

- **Never write a drawable resource ID to disk.** They are regenerated on every
  build. Saves store a stable `artKey` from `RatArt`, resolved at render time.
- **Ask `Roster` about rarity; never keep a second list.** A hand-written copy of
  which species are Legendary is how splicing quietly lost 18 of the 32 species.

## State

Playable and feature-complete enough to walk around with, but not released.
Before a Play upload it still needs a signing config, `versionCode` management,
and minification turning on for release builds.
