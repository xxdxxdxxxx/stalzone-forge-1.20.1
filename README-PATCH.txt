ZoneWars Forge compile patch v3

Extract this archive directly into C:\stalzone-forge and confirm overwrite.
Then run:
  .\gradlew.bat clean build --stacktrace 2>&1 | Tee-Object build-log-v3.txt

Fixes the seven errors from build v2: overworld, BlockPos.above, BlockState.is, and command source execution mappings.
