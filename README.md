# Transparent Telegram — LSPosed модуль

**Найдена и запатчена настоящая причина** (по реальным логам с
устройства, диагностическая версия дампила дерево View): непрозрачный
слой — не `DecorView` и не первые уровни иерархии, а конкретный View
на 8 уровней глубже (`org.telegram.ui.MainTabsActivity$2` /
аналогичные классы под другие экраны), с `ColorDrawable(alpha=255)`,
размером ровно во весь экран. Предыдущие версии патчили только
`DecorView`+1-2 уровня и физически не доставали до настоящей стены.

Текущий подход (`stripOpaqueBackgrounds`):
1. `Window.setFormat(PixelFormat.TRANSLUCENT)` + `FLAG_SHOW_WALLPAPER`
   + полупрозрачный фон окна (как раньше — не мешает, но само по себе
   не решает).
2. После layout (`root.post(...)`, чтобы `getWidth()/getHeight()` уже
   были посчитаны) — полный рекурсивный обход всего дерева `View`.
3. Для каждого `View` с непрозрачным фоном (`alpha==255`, либо
   `Drawable.getOpacity()==OPAQUE` для не-`ColorDrawable`), чей размер
   ≥85% от размера экрана — снижаем альфу через `Drawable.mutate()
   .setAlpha()` (работает для любого типа `Drawable`, включая обои
   чата, не только сплошных цветов).
4. Порог 85% и полный обход специально НЕ трогают мелкие элементы
   (кнопки, пузыри сообщений, карточки) — у них меньший размер, чат
   останется читаемым.
5. Повторяется на каждый `onResume` — переживает смену вкладок/тем.
6. Отдельно: блюр-плашки Telegram (`org.telegram.ui.Components.blur3.
   BlurredBackgroundWithFadeDrawable` и подобные, под статус-баром/
   шапкой) не считаются "непрозрачными" (их `opacity != OPAQUE`) и не
   попадают в общий фильтр по размеру (они маленькие — узкая полоса).
   Раньше они блюрили обычный фон чата, с живой обоиной сзади дают
   "пересвеченный" эффект. Ловим их отдельно по имени класса
   (`isBlurDrawable`, ищет "blur" в имени класса дровейбла) и приглушаем
   через `setAlpha(BLUR_ALPHA)` сильнее, чем обычную стену.

Диагностический дамп (`[DIAG]`) оставлен в коде — пригодится, если для
другого форка/экрана "стена"/блюр окажутся другого размера/класса и
текущие фильтры не сработают.

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
