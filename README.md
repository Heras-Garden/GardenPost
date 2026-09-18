# GardenPost

Physical mail, mailboxes, parcels, and courier delivery for The Garden SMP.

## Requirements

- Java 25
- Paper 26.2
- GardenCore and GardenLands

## Build

```bash
mvn clean package
```

Built JARs are written to `target/`.

## Releases

Tags named `vX.Y.Z` build a release JAR and attach it to a GitHub Release. GardenUpdater reads those releases and stages newer versions for the next server restart.
