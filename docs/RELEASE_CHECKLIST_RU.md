# ZoneWars — release checklist

## 1. Подготовка ветки

```powershell
git checkout main
git pull origin main
git status
```

Должно быть:

```text
nothing to commit, working tree clean
```

## 2. Версия

Проверь `gradle.properties`:

```properties
mod_version=...
```

Если это публичный релиз, увеличь версию перед сборкой.

## 3. Тесты

```powershell
.\gradlew.bat clean test
```

Ожидается:

```text
BUILD SUCCESSFUL
```

## 4. Полная сборка

```powershell
.\gradlew.bat clean build
```

Если Windows падает на `reobfJar` из-за памяти:

```powershell
.\gradlew.bat clean build --no-daemon --max-workers=1
```

Или используй artifact из успешного GitHub Actions run.

## 5. Runtime smoke test

Минимум проверить:

```text
/zw state
/zw join red
/zw start
/zwa capturedebug
/zw validatekits
```

Проверить лог:

```text
Registered TaCZ Forge event
```

## 6. Файлы релиза

Основной JAR:

```text
build/libs/zonewars-*.jar
```

Не прикладывай внешние моды без разрешения на распространение.

## 7. Перед публикацией

- [ ] GitHub Actions зелёный.
- [ ] Runtime checklist пройден.
- [ ] Версия обновлена.
- [ ] Известные проблемы указаны в release notes.
- [ ] `config/zonewars` сервера сохранён в backup.

## 8. После обновления сервера

- [ ] Клиенты получили тот же JAR.
- [ ] Сервер стартует без crash report.
- [ ] `/zw validatekits` успешен.
- [ ] Старые `arena.json`, `clans.json`, `player_stats.json` читаются.
- [ ] На случай ошибки есть backup старого JAR и `config/zonewars`.
