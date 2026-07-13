# ZoneWars — памятка администратора сервера

## Версии

Рекомендуемый стек:

```text
Minecraft 1.20.1
Forge 47.4.0
Java 17
TaCZ 1.1.8-hotfix
```

Клиент и сервер должны использовать один и тот же JAR ZoneWars. После изменения сетевого протокола старый клиент может не подключиться к новому серверу.

## Установка

1. Собрать мод:

```powershell
.\gradlew.bat clean build
```

2. Скопировать JAR:

```text
build/libs/zonewars-0.2.0-alpha.jar
```

в:

```text
minecraft/mods
```

3. Установить TaCZ gun pack без распаковки:

```text
minecraft/tacz/Stalker-Pack1.0.1-Rework.zip
```

## Основные команды игроков

```text
/zw join auto|red|blue
/zw leave
/zw state
/zw balance
/zw stats
/zw shop
/zw buy <item>
/zw respawn base|tent|outpost|confirm
/squad create|invite|join|leave|info
/sc <message>
/clan create|join|leave|stats|info
/cc <message>
```

## Основные команды админа

```text
/zw start
/zw stop
/zw kit <kit>
/zw validatekits
/zw reload
/zwa set redspawn|bluespawn
/zwa point add <id> <name> <radius>
/zwa shop add
/zwa capturedebug
```

## Настройка арены

1. Встань на место RED spawn:

```text
/zwa set redspawn
```

2. Встань на место BLUE spawn:

```text
/zwa set bluespawn
```

3. Встань в центр точки:

```text
/zwa point add alpha Alpha 9
```

4. Встань возле магазина:

```text
/zwa shop add
```

5. Перезагрузи арену при ручном редактировании JSON:

```text
/zw reload
```

## Диагностика точек

Если точка не захватывается:

```text
/zwa capturedebug
```

Смотри поля:

- `phase` должен быть `ACTIVE` или `OVERTIME`;
- `team` не должен быть `NONE`;
- `world=true`;
- `inside=true`;
- `status=CAPTURING` или `OWNED`.

Если `inside=false`, проверь радиус, координаты и высоту точки.

## Диагностика TaCZ

В `logs/latest.log` должны быть строки:

```text
Registered TaCZ Forge event
```

Если есть:

```text
Could not register TaCZ event
```

значит версия TaCZ изменила API событий. Тогда generic Forge damage/death events продолжат работать частично, но точная интеграция TaCZ может быть неполной.

## Сохранения

ZoneWars хранит данные в:

```text
config/zonewars/
```

Основные файлы:

```text
arena.json
clans.json
player_stats.json
matches.json
```

При записи создаются `.bak`-копии. Не удаляй `.bak`, если сервер недавно падал или JSON был повреждён.

## Рекомендации

- Не редактируй JSON во время работы сервера.
- Делай backup `config/zonewars` перед обновлением JAR.
- Проверяй `/zw validatekits` после обновления TaCZ или gun pack.
- Для публичного сервера проверяй runtime-чеклист перед релизом.
