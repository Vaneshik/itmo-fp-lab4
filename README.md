# clj-torrent-client (Full‑stack на Clojure/ClojureScript)

Учебный проект: **BitTorrent-клиент (скачиватель)** на **Clojure** + **веб‑интерфейс на ClojureScript**.  
Backend реализует “движок” загрузки (парсинг `.torrent`, общение с трекерами и пирами, запись на диск), frontend — панель управления (добавить торрент, прогресс, пиры, логи).

> Важно: используйте приложение только для контента, на который у вас есть права (например, Linux ISO, public domain, собственные файлы).  

---

## Содержание

- [Зачем проект](#зачем-проект)
- [Возможности](#возможности)
- [Ограничения](#ограничения)
- [Архитектура](#архитектура)
- [Структура репозитория](#структура-репозитория)
- [Быстрый старт (Dev)](#быстрый-старт-dev)
- [Сборка (Prod)](#сборка-prod)
- [Web UI (что есть на фронте)](#web-ui-что-есть-на-фронте)
- [HTTP API (для фронта)](#http-api-для-фронта)
- [Конфигурация](#конфигурация)
- [Тестирование](#тестирование)
- [Roadmap](#roadmap)
- [Правила работы в команде](#правила-работы-в-команде)

---

## Зачем проект

Цель — показать, как на Clojure можно построить прикладную систему с:
- **чистым функциональным ядром** (piece/state/selection),
- отделёнными **эффектами** (TCP/HTTP/FS/время),
- конкурентностью (например, `core.async` или пул потоков),
- полноценным **front + back** на одном стеке (Clojure + ClojureScript).

---

## Возможности

### Backend (Clojure)
- Чтение `.torrent` (bencode decode)
- Вычисление `info_hash` (SHA1 от `info`)
- HTTP announce к tracker’ам, получение списка пиров
- TCP соединения к пирам и минимальный Peer Wire Protocol:
  - `handshake`
  - `bitfield`, `have`
  - `interested`, `choke/unchoke`
  - `request`, `piece`
  - `keep-alive`
- Загрузка pieces/blocks, проверка SHA1 каждого piece
- Запись на диск (single-file или multi-file — зависит от текущей реализации)
- Сессии загрузки: start/stop/pause/resume (если реализовано)

### Frontend (ClojureScript)
- Добавить торрент (загрузить `.torrent` файлом или указать путь/URL — зависит от реализации)
- Таблица активных загрузок (progress, скорость, ETA)
- Страница торрента: список пиров, pieces (опционально), события/логи
- Управление: pause/resume/stop/remove

---

## Ограничения

Чтобы проект оставался учебным и реализуемым, обычно **не делаем**:
- DHT (поиск пиров без трекера)
- UDP-трекеры (только HTTP)
- uTP/PEX/шифрование протокола
- “как в настоящих клиентах”: сложная очередь, приоритеты файлов, тонкие настройки

---

## Архитектура

Проект организован как **Full-stack монорепо**:

- **Backend** (Clojure):
  - движок торрента (domain + network + storage)
  - HTTP API для фронта (`/api/*`)
  - отдача статики (собранный фронт) из `resources/public`

- **Frontend** (ClojureScript):
  - SPA, собираемая `shadow-cljs`
  - общается с backend через JSON API

### Поток данных (упрощённо)

1) Пользователь добавляет `.torrent` в UI  
2) Backend парсит metainfo, вычисляет `info_hash`  
3) Backend делает `announce` к HTTP tracker’ам → получает peers  
4) Backend открывает TCP соединения к пирам → скачивает blocks → собирает pieces  
5) `storage` пишет на диск, `verify` проверяет SHA1  
6) UI периодически опрашивает `/api/...` и показывает прогресс

### Принцип “чистое ядро + эффекты”

- **Чистые функции (domain)**:
  - piece/block state transitions
  - piece selection (выбор следующего блока)
  - расчёт прогресса
- **Эффекты**:
  - HTTP к трекеру
  - TCP к пирам
  - файловая система
  - время/логирование

---

## Структура репозитория

Рекомендуемая раскладка (может немного отличаться от фактической, но держим близко):

```
deps.edn
shadow-cljs.edn
src/
  torrent/
    codec/
      bencode.clj
    metainfo.clj
    tracker/http.clj
    peer/
      wire.clj
      conn.clj
    pieces/
      state.clj
      selection.clj
    storage/
      files.clj
      verify.clj
    session.clj
    http_api.clj
    server.clj
resources/
  public/                 ; прод-сборка фронта (index.html, js)
frontend/
  src/
    app/
      core.cljs
      events.cljs         ; если re-frame
      subs.cljs
      views.cljs
test/
  torrent/...
```

---

## Быстрый старт (Dev)

### Требования
- JDK 17+ (или 21+)
- Clojure CLI (`clj`)
- Node.js (для сборки фронта)
- `npx` (обычно идёт с Node)

### 1) Установка зависимостей фронта (если используются npm deps)
Если `shadow-cljs` тянет npm-пакеты:
```bash
npm install
```

### 2) Запуск backend (API + статика)
```bash
clj -M:dev
```

Ожидаем:
- API доступно на `http://localhost:8080`
- (опционально) health: `GET /api/health`

### 3) Запуск frontend в watch режиме
```bash
npx shadow-cljs watch app
```

Обычно UI доступен на:
- `http://localhost:3000` (dev server shadow)
- а API — на `http://localhost:8080`

> Примечание: в dev режиме могут понадобиться CORS настройки на backend или прокси в shadow dev server.

---

## Сборка (Prod)

### 1) Собрать фронт в `resources/public`
```bash
npx shadow-cljs release app
```

### 2) Запустить backend (который раздаёт статику)
```bash
clj -M:run
```

Открыть:
- `http://localhost:8080/` — SPA
- `http://localhost:8080/api/...` — API

---

## Web UI (что есть на фронте)

### Страницы/экраны (минимальный набор)
1) **Dashboard / Torrents**
- список активных торрентов:
  - имя
  - прогресс (%)
  - скачано/всего
  - скорость down/up (если есть)
  - статус (running/paused/stopped/finished)
- действия: pause/resume/stop/remove

2) **Add Torrent**
- загрузить `.torrent` файлом (рекомендуется для учебного UX)
- или указать путь на сервере (если проект так устроен)
- после добавления — редирект на details

3) **Torrent Details**
- метаданные (name, size, pieces count, piece length)
- список peers (ip:port, choked/unchoked, last-seen)
- логи событий (tracker announce, peer connect, verify ok/fail)

### Технологии фронта (предлагаемо)
- `reagent` (+ опционально `re-frame`)
- HTTP запросы через `fetch`/`cljs-ajax`
- роутинг опционально (`reitit-frontend`)

---

## HTTP API (для фронта)

Ниже пример API, которое должен предоставлять backend. Форматы — JSON.

### Health
`GET /api/health`
```json
{"status":"ok"}
```

### Создать сессию загрузки
`POST /api/torrents`

Вариант A (upload `.torrent` файлом): `multipart/form-data`  
- field: `file`

Вариант B (простой учебный): JSON
```json
{"torrentPath":"./examples/sample.torrent","outDir":"./downloads"}
```

Ответ:
```json
{"id":"<uuid>","name":"...","status":"running"}
```

### Список сессий
`GET /api/torrents`
```json
[
  {"id":"...","name":"...","progress":0.42,"status":"running","downSpeed":123456}
]
```

### Детали по сессии
`GET /api/torrents/:id`
```json
{
  "id":"...",
  "name":"...",
  "status":"running",
  "totalBytes":123,
  "downloadedBytes":45,
  "progress":0.36,
  "piecesTotal":1000,
  "piecesDone":360,
  "peers":{"connected":12,"active":8}
}
```

### Пиры
`GET /api/torrents/:id/peers`
```json
[
  {"ip":"1.2.3.4","port":51413,"choked":false,"lastSeen":1730000000}
]
```

### Управление
- `POST /api/torrents/:id/pause`
- `POST /api/torrents/:id/resume`
- `POST /api/torrents/:id/stop`
- `DELETE /api/torrents/:id`

Ответ везде — актуальный статус или `{ok:true}`.

> Важно: API не должно “сливать” приватные данные. Для учебного проекта достаточно без авторизации, но можно добавить простой токен на админку.

---

## Конфигурация

Конфиг хранится в EDN (пример `config/dev.edn`):

```edn
{:http {:port 8080}

 :client {:peer-id-prefix "-CLJ001-"
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

---

## Тестирование

Запуск:
```bash
clj -M:test
```

Что тестируем обязательно:
- `bencode` encode/decode (fixtures)
- вычисление `info_hash` на фиксированном `.torrent`
- `verify` (SHA1 для piece)
- domain state transitions (pieces/blocks)
- selection алгоритм как чистые функции

Интеграционные тесты (по возможности):
- поднять “fake peer” локально и прогнать handshake + одно скачивание блока
- тест API (`/api/torrents`, `/api/torrents/:id`)

---

## Roadmap

Короткий список улучшений (по приоритету):
1) Более устойчивый networking (таймауты, reconnect, лимиты)
2) Поддержка compact peers от tracker’ов полностью
3) Rarest-first selection (или хотя бы “random-first” после старта)
4) Resume (сохранить bitfield/прогресс на диск)
5) Ограничение скорости (token bucket)
6) UDP trackers / DHT (отдельный advanced этап)

---

## Правила работы в команде

- **Доменная логика** (selection/state/verify) — по возможности **чистые функции** + покрытие тестами.
- Сеть/FS — отдельные неймспейсы, минимум логики внутри side-effect кода.
- PR должен содержать:
  - описание изменений
  - как запустить/проверить
  - тесты или объяснение, почему тесты не добавлены
- Имена веток: `feature/...`, `fix/...`, `refactor/...`

---

## Лицензия

Укажите выбранную лицензию (например MIT) в `LICENSE`.
```
