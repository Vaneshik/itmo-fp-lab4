```markdown
# clj-torrent-client

Учебный BitTorrent-клиент (скачиватель) на **Clojure** (backend/движок) + опционально **ClojureScript** UI. Проект предназначен для демонстрации сетевого взаимодействия, функционального ядра и управления состоянием/конкурентностью в Clojure.

> Важно: используйте клиент только для контента, на который у вас есть права (например, open-source ISO, public domain, ваши собственные файлы).

---

## Содержание

- [Возможности](#возможности)
- [Ограничения (что не реализовано)](#ограничения-что-не-реализовано)
- [Архитектура (как это устроено)](#архитектура-как-это-устроено)
- [Быстрый старт](#быстрый-старт)
- [Конфигурация](#конфигурация)
- [CLI команды](#cli-команды)
- [Web UI (опционально)](#web-ui-опционально)
- [Структура проекта](#структура-проекта)
- [Протоколы и форматы](#протоколы-и-форматы)
- [Тестирование](#тестирование)
- [Roadmap](#roadmap)
- [Contributing](#contributing)

---

## Возможности

### Реализовано (MVP)
- Чтение и разбор `.torrent` (bencode decode)
- Вычисление `info_hash` (SHA1 от `info`-словаря)
- Общение с **HTTP tracker**:
  - `announce` (получение списка пиров)
- Подключение к пирам по TCP и базовая реализация BitTorrent Peer Wire Protocol:
  - `handshake`
  - `bitfield`, `have`
  - `interested`, `unchoke/choke`
  - `request`, `piece`
  - `keep-alive`
- Загрузка по кускам (pieces/blocks), проверка SHA1 каждого piece
- Запись на диск (один файл и/или multi-file торренты — зависит от текущей реализации)
- Простой алгоритм выбора кусков (обычно sequential; может быть улучшен)

### Опционально / по веткам
- Web UI (ClojureScript): добавить торрент, смотреть прогресс, список пиров
- Resume (частичное восстановление прогресса)
- Ограничение скорости/соединений

---

## Ограничения (что не реализовано)

Чтобы проект оставался учебным и реализуемым:
- Нет DHT (поиск пиров без трекера)
- Нет UDP-трекеров (только HTTP)
- Нет uTP, шифрования протокола, PEX
- Нет полноценного “торрент-менеджера как у больших клиентов” (очереди, приоритеты файлов, тонкие настройки)

---

## Архитектура (как это устроено)

Проект построен по принципу: **чистое ядро + эффектный слой**.

### Поток данных (упрощённо)

1. `metainfo` читает `.torrent`, парсит `info`, вычисляет `info_hash`, формирует модель раздачи.
2. `tracker` делает HTTP announce → получает список пиров.
3. `peer`-слой открывает TCP соединения к пирам, делает handshake, выясняет доступные pieces.
4. `piece`-слой выбирает следующий piece/block для скачивания.
5. `storage` принимает полученные blocks, собирает pieces, проверяет SHA1, пишет на диск.
6. `session` координирует всё: состояние, конкуренцию, перезапуски, метрики.

### Ключевые компоненты

- **Domain (чистая логика)**  
  - выбор следующего блока/куска
  - обновление состояния “какие pieces есть/нужны”
  - расчёт прогресса
- **Effects**  
  - HTTP запросы к трекеру  
  - TCP сокеты к пирам  
  - файловая система  
  - время, логирование

### Конкурентность
Обычно используется один из подходов:
- `core.async` (каналы: события сети → обработка → команды записи/запроса)
- или `future`/thread pools + очереди сообщений

В README ниже команды и примеры не завязаны на конкретную реализацию, но структура модулей — да.

---

## Быстрый старт

### Требования
- JDK 21+ (или 17+, если проект настроен на него)
- Clojure CLI (`clj`)
- (Опционально для UI) Node.js + `shadow-cljs`

### Клонирование
```bash
git clone https://github.com/<org-or-user>/clj-torrent-client.git
cd clj-torrent-client
```

### Запуск тестов
```bash
clj -M:test
```

### Скачивание (пример)
Используйте `.torrent`, который распространяется легально или создан вами.
```bash
clj -M:run -- download \
  --torrent ./examples/sample.torrent \
  --out ./downloads
```

После запуска приложение:
- прочитает metainfo,
- запросит peers у HTTP-трекера(ов),
- начнёт подключаться к пирам и скачивать pieces,
- будет писать прогресс в консоль (и/или отдавать через API для UI).

---

## Конфигурация

Конфиг читается из EDN (пример: `config/dev.edn`):

```edn
{:client {:peer-id-prefix "-CLJ001-"
          :port 51413
          :max-peers 40
          :numwant 50
          :block-size 16384}

 :timeouts {:peer-handshake-ms 7000
            :peer-idle-ms 45000}

 :storage {:preallocate? false
           :verify-on-start? true}

 :logging {:level :info}}
```

Переопределение:
- через аргументы CLI
- или переменную окружения (если добавлено): `CLJ_TORRENT_CONFIG=...`

---

## CLI команды

> Точные команды зависят от того, как оформлен `-main`. Ниже рекомендуемый интерфейс.

### `download`
Скачать раздачу из `.torrent` в указанную папку.
```bash
clj -M:run -- download --torrent path/to/file.torrent --out ./downloads
```

Опции:
- `--max-peers N` — лимит активных соединений
- `--port P` — локальный порт (если требуется)
- `--log-level debug|info|warn`

### `inspect`
Показать информацию о торренте без скачивания:
```bash
clj -M:run -- inspect --torrent path/to/file.torrent
```

Вывод:
- name, total size
- piece length, pieces count
- announce / announce-list

### `seed` (если реализовано)
Раздавать файл(ы), соответствующие торренту:
```bash
clj -M:run -- seed --torrent path/to/file.torrent --data ./downloads
```

---

## Web UI (опционально)

Если в проекте есть UI, обычно это выглядит так:

### Dev режим
1) Запустить backend API:
```bash
clj -M:run -- api --port 8080
```

2) Запустить фронт:
```bash
npx shadow-cljs watch app
```

Открыть:
- UI: `http://localhost:3000`
- API: `http://localhost:8080/api/*`

### Пример API (типично)
- `GET /api/torrents` — список активных сессий
- `POST /api/torrents` `{torrentPath|torrentBytes}` — добавить торрент
- `GET /api/torrents/:id` — прогресс/скорость/пиры
- `POST /api/torrents/:id/pause|resume|stop`

---

## Структура проекта

Примерная раскладка:

```
src/
  torrent/codec/
    bencode.clj            ; bencode decode/encode
  torrent/metainfo.clj     ; parse .torrent, info_hash
  torrent/tracker/http.clj ; announce к HTTP tracker
  torrent/peer/
    wire.clj               ; кодирование/декодирование peer messages
    conn.clj               ; TCP соединение, handshake, чтение/запись
  torrent/pieces/
    selection.clj          ; выбор piece/block
    state.clj              ; состояние pieces/blocks
  torrent/storage/
    files.clj              ; mapping piece->file offsets, запись
    verify.clj             ; SHA1 verify
  torrent/session.clj      ; оркестрация, жизненный цикл
  torrent/cli.clj          ; CLI entrypoints
  torrent/http_api.clj     ; (опц) REST для UI
resources/
  public/                  ; (опц) собранный фронт
examples/
  sample.torrent           ; пример для тестов/демо (легальный контент/заглушка)
test/
  torrent/...
```

---

## Протоколы и форматы

### `.torrent` (metainfo)
- bencode словарь
- ключевой словарь `info`:
  - `piece length`
  - `pieces` (concatenated SHA1 hashes)
  - `name`
  - `length` или `files` (для multi-file)

`info_hash` = `SHA1(bencode(info))`

### Tracker (HTTP)
Поддерживается:
- `announce` запросы к URL из `announce`/`announce-list`
- разбор bencode ответа
- обработка `peers` (обычный или compact — зависит от реализации)

### Peer Wire Protocol
Минимально необходимые сообщения для скачивания:
- handshake → interested → unchoke → request/piece цикл

---

## Тестирование

Запуск:
```bash
clj -M:test
```

Рекомендуемые группы тестов:
- `bencode` encode/decode
- `metainfo` вычисление info_hash (фиксированные fixtures)
- piece hashing/verification
- выбор кусков (selection) как чистые функции
- (интеграционные) имитация peer wire на локальном “fake peer” (если есть)

---

## Roadmap

Ближайшие улучшения:
- Поддержка compact peers полностью (если ещё нет)
- Улучшенный piece selection (rarest-first)
- “Endgame mode” (дублирование последних запросов)
- Resume: хранить bitfield/прогресс на диск
- Ограничение скорости (token bucket)
- UDP trackers / DHT (как отдельные расширения, если хватит времени)

---

## Contributing

1) Создайте ветку `feature/<name>`
2) Пишите тесты на доменную логику (selection/state/verify)
3) Отделяйте чистые функции от IO (сеть/файлы/время)
4) PR должен включать:
   - краткое описание
   - как протестировать
   - ограничения/побочные эффекты

---

## Лицензия

MIT (или другая, укажите здесь).
```

Если скажешь, какие у вас уже есть решения по стеку (core.async или futures, нужен ли Web UI, один торрент за раз или несколько), я подгоню README под ваш фактический `deps.edn`, алиасы (`:run`, `:test`) и реальные команды запуска.
