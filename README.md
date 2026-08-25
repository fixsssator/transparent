# Transparent Telegram — LSPosed модуль

Воспроизводит эффект "прозрачной темы" (окно показывает системные обои
позади интерфейса) для Telegram-based клиентов через runtime-хук
`org.telegram.ui.LaunchActivity.onCreate()`, без патчинга ресурсов
конкретного APK.

Технически: главный фикс — `Window.setFormat(PixelFormat.TRANSLUCENT)`.
Одного `Window.addFlags(FLAG_SHOW_WALLPAPER)` + `setBackgroundDrawable(...)`
недостаточно: без явного переключения формата окна в TRANSLUCENT окно
остаётся непрозрачным на уровне композитинга, и обои системы физически
не могут просвечивать — именно это было упущено в первой версии модуля,
из-за чего эффект не проявлялся на AyuGram.

Почему не через `XC_InitPackageResources`/`XResources`: этот механизм
перехватывает `Resources.getColor()/getDrawable()` для именованных
ресурсов, а `android:windowShowWallpaper`/`colorBackground` — это
атрибуты внутри `<style>`, которые Android резолвит через
`obtainStyledAttributes()` в нативном коде при создании `PhoneWindow`,
до вызова любых Java-методов `Resources`, доступных для перехвата этим
способом. Технически можно долбить `TypedArray.mData` через рефлексию,
но раскладка этого массива зависит от версии Android/прошивки —
слишком хрупко. `Window#setFormat()` — публичный официальный API,
дающий тот же результат надёжнее.

Список целевых пакетов и настройка альфы — в
`app/src/main/java/com/example/transparenttelegram/HookEntry.java`,
константы `TARGET_PACKAGES` и `WINDOW_BACKGROUND_COLOR`.

## Собрать самому (Android Studio, ~2 минуты)

У меня в песочнице нет доступа к Android SDK и к репозиториям
Google/Maven (нужны для сборки Android-проектов), поэтому собрать
готовый бинарник сам я не могу — но у вас на своей машине это займёт
пару минут:

1. Открыть папку `TransparentTelegram` в Android Studio (File -> Open).
2. Дождаться синхронизации Gradle (сама подтянет всё нужное).
3. Build -> Build Bundle(s)/APK(s) -> Build APK(s).
4. Готовый файл — в `app/build/outputs/apk/debug/app-debug.apk`.

Либо из командной строки, если стоит Android SDK/JDK 17:

```
cd TransparentTelegram
gradle assembleDebug
```

## Собрать через GitHub Actions (автоматически, в облаке)

В проекте уже лежит `.github/workflows/build.yml`. Если запушить эту
папку в свой репозиторий на GitHub:

1. Actions соберут `app-debug.apk` автоматически при пуше в main/master,
   либо вручную через Actions -> Build APK -> Run workflow.
2. Готовый APK появится в артефактах запуска (Actions -> конкретный run
   -> Artifacts -> TransparentTelegram-debug-apk).

Это debug-сборка (самоподписанная debug-ключом Android) — этого
достаточно для локальной установки и LSPosed, в Google Play её
заливать не нужно.

## Установка на телефон

1. Установить и активировать LSPosed (поверх Magisk/KernelSU/APatch).
2. Установить собранный `app-debug.apk`.
3. LSPosed Manager -> Modules -> включить "Transparent Telegram (LSPosed)".
4. Там же открыть Scope и отметить нужные приложения (список
   рекомендованных — прямо в самом модуле, на главном экране, и в
   HookEntry.TARGET_PACKAGES).
5. Перезапустить отмеченные приложения (форс-стоп или простой рестарт).

## Если не сработало для конкретного форка

Хук ищет ровно класс `org.telegram.ui.LaunchActivity`. У большинства
форков (AyuGram, Nekogram, NekoX — проверено по исходникам/манифестам)
он сохраняется, потому что они форкают DrKLO/Telegram и не
переименовывают базовые классы. Если у какого-то клиента это не так —
в логе LSPosed Manager -> Logs будет строка
`[TransparentTelegram] failed for <package>: ...` с причиной.
