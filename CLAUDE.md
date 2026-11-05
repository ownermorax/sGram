# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Debug сборка (arm64-v8a, быстро)
./gradlew :TMessagesProj_App:assembleAfatDebug -Pabis=arm64-v8a

# Debug сборка (все ABI)
./gradlew :TMessagesProj_App:assembleAfatDebug

# Release сборка
./gradlew :TMessagesProj_App:assembleAfatRelease

# Android App Bundle (release)
./gradlew :TMessagesProj_App:bundleBundleAfatRelease

# Bundle (minSdk 23)
./gradlew :TMessagesProj_App:bundleBundleAfat_SDK23Release

# Standalone (.web suffix)
./gradlew :TMessagesProj_App:assembleAfatStandalone

# Сборка только библиотеки (без приложения)
./gradlew :TMessagesProj:assembleDebug

# Инструментационные тесты (требуют эмулятор/устройство)
./gradlew :TMessagesProj_AppTests:connectedAfatDebugAndroidTest

# Очистка
./gradlew clean

# Docker-сборка (все варианты, включая Huawei)
docker build -t sgram-builder .
docker run sgram-builder

# Ускоренная сборка с профилированием
./gradlew :TMessagesProj_App:assembleAfatDebug --build-cache --parallel --max-workers=8
```

## Архитектура

### Модульная структура

```
settings.gradle
 ├── :TMessagesProj          (Android library) — ядро: вся логика, UI, JNI
 ├── :TMessagesProj_App      (Android app) — тонкая обёртка, точка входа
 └── :TMessagesProj_AppTests (Android tests) — инструментационные тесты
```

### Пакеты в `org.telegram` (в `:TMessagesProj`)

| Пакет | Описание |
|---|---|
| **`messenger`** | Ядро — ApplicationLoader, MessagesController, FileLoader, ConnectionsManager, SharedConfig, AccountInstance, NotificationCenter, VoIP, MediaController, SendMessagesHelper, BuildVars, секретные чаты, локализация, уведомления |
| **`ui`** | Все UI: ActionBar, Components, Cells, Stories, Charts, Gifts, Stars, TON, Business, bots, web, LNavigation (боковое меню) |
| **`ui/ActionBar`** | ActionBar, BottomSheet, BaseFragment, Activity, диалоги |
| **`ui/Components`** | Переиспользуемые компоненты: аватары, стикеры, эмодзи, клавиатуры |
| **`ui/Cells`** | Вьюхи для списков: чат, пользователь, настройки |
| **`tgnet`** | Сетевой протокол MTProto, TL-схемы (в `tl/`), JSON (в `json/`) |
| **`SQLite`** | Обёртка БД |

## Ключевые архитектурные паттерны

### Как добавить новый экран (BaseFragment)

Все экраны наследуются от `BaseFragment` (в `ui/ActionBar/`). Базовый шаблон:

```java
public class MyNewActivity extends BaseFragment {
    private int currentAccount = UserConfig.selectedAccount;

    @Override
    public View createView(Context context) {
        // 1. Создать行动 bar
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("My Screen");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        // 2. Фрагментное состояние
        fragmentView = new FrameLayout(context);
        // ... наполнить UI

        return fragmentView;
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
    }
}
```

Чтобы открыть: `presentFragment(new MyNewActivity())` из любого BaseFragment, или `MainTabsActivity.getInstance().presentFragment(new MyNewActivity())` из UI-контекста.

Варианты:
- **BottomSheet** (в `ui/ActionBar/`) — модальные диалоги снизу. Отнаследоваться и реализовать `createView()`.
- **AlertDialog** (в `ui/ActionBar/`) — простые диалоги. Строятся билдером.
- **LaunchActivity** (в `ui/`) — корневая Activity.

### Tab-система (MainTabsActivity)

Вкладки управляются в `ui/MainTabsActivity.java`:
- `POSITION_CHATS = 0` — чаты
- `POSITION_SGRAM = 1` — sGram-меню (заменяет Контакты)
- `POSITION_CONTACTS = 2` — контакты
- `POSITION_CALLS = 3` — звонки

Добавление новой вкладки: расширить `INavigationLayout` и зарегистрировать в onCreate/BottomBar инициализации.

### Preferences

Два способа хранения настроек:

1. **SharedConfig** (`messenger/SharedConfig.java`) — для настроек Telegram (тёмная тема, язык, уведомления). Статические поля, загружаются при старте через `loadConfig()` / `loadGlobalConfig()`. Добавить поле → загрузить/сохранить в `loadConfig()`.

2. **`sgram_prefs` SharedPreferences** — для настроек sGram (VPN, кастомные фичи). Доступ:
```java
SharedPreferences prefs = ApplicationLoader.applicationContext
    .getSharedPreferences("sgram_prefs", Context.MODE_PRIVATE);
prefs.edit().putBoolean("vpn_enabled", true).apply();
```

Klючи sGram: `vpn_enabled`, `vpn_paused`, `vpn_only_tg`, `vpn_excluded_apps` (Set\<String\>), `wg_priv_key`, `wg_pub_key`, `wg_endpoint`, `wg_address`, `wg_dns`, `wg_allowed_ips`, `conf_name`, `all_time_rx`, `all_time_tx`, `bypass_all`.

### Локализация (строки)

Строки добавляются в `TMessagesProj/src/main/res/values/strings.xml`:
```xml
<string name="SgramSettings">sGram</string>
```

Используются через `LocaleController.getString("SgramSettings", R.string.SgramSettings)` или в layout через `@string/SgramSettings`.

Важно: `LocaleController.getString()` возвращает строку с учётом языка — `context.getString()` вернёт only English.

### Темизация

Система тем в `ui/ActionBar/Theme.java`, цвета в `ui/ActionBar/ThemeColors.java`:
- Тёмная тема: `Theme.THEME_FLAG_DARK`
- `Theme.getColor(Theme.key_windowBackgroundWhite)` — получить цвет в runtime
- `ThemeDescription` — подписка на изменения темы (автообновление UI)
- `EmojiThemes` (`ui/ActionBar/EmojiThemes.java`) — темы с эмодзи-акцентами (Telegram Premium/emoji themes)

### NotificationCenter (Event Bus)

Событийная шина приложения. Ключевые наблюдатели:
```java
// Подписка
NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didUpdateConnectionState);

// Рассылка
NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didUpdateConnectionState);

// Отписка
NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didUpdateConnectionState);
```

Основные события: `didUpdateConnectionState`, `mainUserInfoChanged`, `contactsDidLoaded`, `fileLoaded`, `updateInterfaces`, `closeChats`, `proxySettingsChanged`, `reloadHints`, `didSetNewWallpapper`, `needReloadRecentStickers`.

### JNI / Нативный код

- Точка входа: `jni/NativeLoader.cpp` → `jni/jni.c` (bridge)
- Сборка через NDK 21.4.7075529, CMakeLists.txt в `jni/CMakeLists.txt`
- Внешние статические библиотеки: ffmpeg, boringssl (TLS), tgvoip, tgcalls, rlottie, sqlite, flac, mozjpeg, tde2e, libvpx, libdav1d
- Java native методы живут в `org.telegram.messenger.NativeLoader.java`

### TL-схемы и кодогенерация

TL-B схемы в `TMessagesProj/tl/`. Кодогенерация через Gradle-плагин `test-generator` в `buildSrc/`:
- Парсинг: `SchemeAllLayersParser`, `SchemeJsonParser`, `SchemeTlValidator`
- Генерация: `SchemeCodeGen` (Kotlin модели), `TelegramCodeParser` (Telegram-специфичные)
- Результат: Kotlin-классы в `TMessagesProj_AppTests/src/androidTest/kotlin/org/telegram/tgnet/model/generated/`
- Таск: `./gradlew generateScheme`

## sGram-specific modifications

### Новые файлы (созданы с нуля)

| Файл | Назначение |
|---|---|
| `messenger/SgramVpnManager.java` | Менеджер WireGuard VPN (синглтон) |
| `messenger/SgramVpnService.java` | Foreground-сервис с уведомлением о трафике |
| `messenger/SgramGhostController.java` | Статический хелпер Ghost Mode (online, read, stories, typing). Каждый тумблер независим (нет мастер-ключа), по умолчанию false |
| `messenger/SgramSpeedController.java` | Статический хелпер Speed Boost toggle, по умолчанию false |
| `messenger/BetaUpdate.java` | Механизм бета-обновлений через AppCenter |
| `ui/SgramMenuActivity.java` | Экран настроек sGram (WireGuard, Restrictions Bypass, Ghost Mode, Speed Boost) |
| `ui/SgramAppSelectActivity.java` | Выбор приложений для исключения из VPN |
| `sgram/SimpleTextViewSwitcher.java` | ViewSwitcher для анимированного переключения текста |

### Модифицированные области (ключевые)

| Файл | Изменение |
|---|---|
| `jni/tgnet/Defines.h` + `ConnectionsManager.cpp` | MAX_ACCOUNT_COUNT=16 (было 5), добавлены case 5..15 |
| `messenger/FileLoadOperation.java` | Speed Boost: точные значения ayuGram — ultra (1MB, 12/12), normal (512KB, 8/8), off (128KB, 4/4). Полевые defaults: 32768/131072/4/4. `updateParams()` — 3-tier: preloadPrefixSize/experimental+!forceSmallChunk → ultra, boost → normal, off → stock |
| `jni/tgnet/Defines.h` | READ_BUFFER_SIZE 2MB→6MB, DOWNLOAD_CONNECTIONS_COUNT 2→32, UPLOAD_CONNECTIONS_COUNT 4→24 |
| `messenger/MessagesController.java` | Ghost Mode: 4 независимых тумблера. **Важно:** `ignoreSetOnline` RESET при выключении ghost_no_online (else { ignoreSetOnline = false; }) |
| `ui/Stories/StoriesController.java` | Ghost Mode: `isGhostNoStories()` guard moved BEFORE `seenStories.add()` и `saveCache()` в `markAsRead()`, а также BEFORE локального обновления `max_read_id`/`dialogIdToMaxReadId`/`storiesStorage.updateMaxReadId()` в `markStoryAsRead()`. Это позволяет повторно отправить просмотр после выключения тумблера |
| `ui/ProfileActivity.java` | Отображение ID, дата регистрации, sGram verification badge |
| `messenger/LocaleController.formatNumber()` | Отключение округления чисел |
| `ui/MainTabsActivity.java` | Вкладка sGram (позиция 1, заменяет Контакты) |
| `ui/LaunchActivity.java` | Интеграция VPN (SgramVpnManager) в onCreate |

### Bypass All — единый тумблер обхода ограничений

Один тумблер `bypass_all` в `sgram_prefs` вместо трёх (bypass_restrictions, bypass_secure, bypass_ttl).

**Метод-хелпер:** `MessagesController.isBypassAll()` (статика, читает `bypass_all` из `sgram_prefs`).

**Что делает:**
- `isChatNoForwards()` / `isUserNoForwards()` возвращают `false` — обход noforwards
- FLAG_SECURE не ставится — скриншоты везде разрешены
- `sendSecretMessageRead()` + `sendSecretMediaDelete()` возвращают null — View Once не удаляются
- `allowScreenshots()` в историях возвращает `true` — Save to Gallery работает всегда
- `allowShare` в историях не требует `isPublic` — приватные истории можно сохранять
- galleryButton в PhotoViewer принудительно VISIBLE для TTL-медиа
- Уведомления о скриншотах не отправляются
- SecretMediaViewer: кнопка Save (msg_gallery) в action bar; таймер/подсказка сдвигаются левее на 52dp, чтобы их не перекрывала кнопка

| Файл | Что меняли |
|---|---|
| `ui/SgramMenuActivity.java` | 3 тумблера → 1 "Bypass All" (позиция 5, большой TextCheckCell 20sp как WireGuard, без отдельного HeaderCell). UI: отступы **21dp у Config/Excluded Applications** (TextSettingsCell по умолчанию padding=21dp + setPadding(21dp) = 42dp total, совпадает с под-тумблерами); Ghost Mode header без отступа (padding=0); под-тумблеры Ghost Mode с отступом 41dp **при padding=1** у TextCheckCell. **Важно:** TextCheckCell small создаётся с `new TextCheckCell(mContext, 1)` (padding=1), textView setPadding(41dp) = 42dp total. TextSettingsCell — `new TextSettingsCell(mContext)` (padding=21), textView setPadding(21dp) = 42dp total. |
| `messenger/MessagesController.java` | 3 helpers → `isBypassAll()`, обновлены `isChatNoForwards/isUserNoForwards` |
| `ui/ChatActivity.java` | `sendSecretMessageRead` + `sendSecretMediaDelete` → return null при bypass |
| `ui/Stories/PeerStoriesView.java` | `allowScreenshots()` bypass + `allowShare` не требует `isPublic` |
| `ui/PhotoViewer.java` | bypass в galleryButton (2 места + canPaint) |
| `ui/Stories/StoryViewer.java` | bypass FLAG_SECURE |
| `ui/SecretMediaViewer.java` | bypass FLAG_SECURE + кнопка Save (msg_gallery, id=100) в action bar. `saveCurrentMedia()` через `FileLoader.getPathToMessage()` + `MediaController.saveFile()`. Таймер сдвигается влево на 52dp при bypass_all, чтобы не перекрывать Save.
| `ui/Components/TranslateAlert2.java` | bypass FLAG_SECURE + text selection |
| `messenger/FileLoader.java` | bypass noforwards в `canSaveToPublicStorage` |
| `messenger/MediaController.java` | bypass screenshot notification |

### BuildVars

```java
APP_ID = 29796858
APP_HASH = "f6187dc7bed763e0a24eea15ca08c1f8"
```

## Сборка и деплой

### Варианты сборки

**Product flavors** (minApi dimension): `afat` (APK, minSdk 21), `bundleAfat` (AAB), `bundleAfat_SDK23` (AAB, minSdk 23)

**Build types**: `debug` (.beta, debuggable), `HA_private`/`HA_public`/`HA_hardcore` (AppCenter, minified, proguard-rules-beta.pro), `standalone` (.web), `release` (production)

### Ключи подписи

Все сборки подписываются `TMessagesProj/config/release.keystore` (пароль `android`). Для продакшена — свой keystore.

### CI (GitHub Actions)

- Файл: `.github/workflows/build-apk.yml`
- Собирает `assembleAfatDebug` с arm64-v8a на CI
- Использует тестовые API-ключи (APP_ID=6)
- Результат: `sgram.apk` (3 дня хранения)

## Известные проблемы и решения

### ForegroundServiceDidNotStartInTimeException (SgramVpnService)

**Ошибка:** `startForeground()` не вызван вовремя на API 35.

**Причина:** `createNotificationChannel()` — это binder call к system_server, который может блокироваться. Если он был вызван **до** `startForeground()`, система не успевала получить уведомление в таймаут.

**Фикс (2026-07-14):**
1. `startForeground()` вызывается **первым действием** в `onCreate()` — **до** `createNotificationChannel()`
2. После `startForeground()` вызывается `createNotificationChannel()`, затем `nm.notify()` для обновления уведомления с контентом
3. `startForeground()` в `onStartCommand()` обёрнут в try-catch
4. `startForegroundService()` в `SgramVpnManager` обёрнут в try-catch
5. **AndroidManifest** (`TMessagesProj_App/src/main/AndroidManifest.xml`): `android:foregroundServiceType="specialUse"` + `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">`
6. Пермишены: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`

### Пробел в пути — ошибка линковки JNI

Путь к проекту не должен содержать пробелов — CMake линковка ломается на `${CMAKE_HOME_DIRECTORY}` с пробелом.

### checkVisibility task

Блокирует сборку публичных вариантов на приватных форках. Учитывать при настройке CI.

## Gradle Properties

```
APP_VERSION_CODE=6666
APP_VERSION_NAME=12.6.4
APP_PACKAGE=org.telegram.messenger
AGP 8.6.1, Kotlin 1.9.20
AndroidX, Jetifier, R8 full mode
JVM: -Xmx4096M, parallel builds
```

## Требования к окружению

- **Android SDK**: API 35, build-tools 35.0.0
- **NDK**: 21.4.7075529
- **CMake**: 3.10.2+
- **Gradle**: 8.6.1 (+ wrapper)
- **Java**: 8 (source/target совместимость)
- **Путь**: без пробелов (иначе CMake ломается)
- **Файл API_KEYS** в корне с APP_ID, APP_HASH, MAPS_V2_API, SIGNING_* кредами