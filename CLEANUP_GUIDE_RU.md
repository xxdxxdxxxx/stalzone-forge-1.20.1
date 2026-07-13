# Безопасная очистка проекта

## Можно удалить сейчас

Из корня проекта:

```text
APPLY_INSTRUCTIONS_RU.txt
STALZONE_CONTINUED_STATUS_RU.txt
stalzone-source.zip
zonewars-fabric-port.patch
```

Это старые инструкции, промежуточный архив и устаревший патч. Актуальная информация перенесена в новые `README.md` и `PROJECT_CONTEXT.md`.

Из тестового сервера можно регулярно очищать:

```text
dev-fabric-tacz-server/logs/
dev-fabric-tacz-server/crash-reports/
```

## Можно удалить при необходимости, но потом потребуется пересборка

```text
zonewars-client/build/
zonewars-fabric/build/
campchat-fabric/build/
zonewars-server/build/
.gradle/
```

Удаление этих папок безопасно для исходников, но следующая Gradle-сборка займёт больше времени.

## Пока не удалять

```text
.git/
build/
gradle/
gradlew
gradlew.bat
campchat-fabric/
zonewars-client/
zonewars-fabric/
zonewars-pack/
zonewars-server/
scripts/
dev-fabric-tacz-server/
dev-fabric-tacz-server/config/
dev-fabric-tacz-server/world/
dev-fabric-tacz-server/backups/
README.md
PROJECT_CONTEXT.md
settings.gradle.kts
build.gradle.kts
gradle.properties
```

Особенно важно: корневая папка `build/` содержит JDK 21 и кэш TaCZ-модов, это не обычный Gradle build output.

## Legacy Paper

`dev-server/` можно переместить за пределы репозитория, если Fabric-путь окончательно принят. Рекомендуется сначала перенести, а не удалять:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\Documents\stalzone-legacy" | Out-Null
Move-Item ".\dev-server" "$env:USERPROFILE\Documents\stalzone-legacy\dev-server"
```

Сам модуль `zonewars-server/` пока оставить как эталон старой механики.

## Большие world backups

`dev-fabric-tacz-server/backups/` может занимать много места. Удалять резервные миры стоит только после проверки нового плоского мира. Сначала посмотрите размер:

```powershell
Get-ChildItem ".\dev-fabric-tacz-server\backups" -Directory | ForEach-Object {
    $size=(Get-ChildItem $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum
    [PSCustomObject]@{Name=$_.Name; SizeMB=[math]::Round($size/1MB,2)}
}
```
