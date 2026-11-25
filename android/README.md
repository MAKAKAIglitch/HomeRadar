# Android application

Начальный Android-модуль HomeRadar.

## Требования

- JDK 17.
- Android SDK Platform 37.
- Gradle 9.5.0.
- Android Gradle Plugin 9.3.1.
- Kotlin Gradle Plugin / Compose compiler 2.4.21.

## Сборка

В репозитории зафиксирован `gradle-wrapper.properties`, но wrapper JAR и скрипты `gradlew` пока не добавлены. Если Gradle 9.5.0 установлен локально, из корня репозитория выполни:

```bash
gradle wrapper --gradle-version 9.5.0
./gradlew :android:app:assembleDebug
```

На Windows после генерации wrapper:

```powershell
gradle wrapper --gradle-version 9.5.0
.\gradlew.bat :android:app:assembleDebug
```

## Реализовано

- Android Activity и Compose Material 3 UI.
- Запрос `ACCESS_FINE_LOCATION` перед чтением Wi-Fi scan results.
- Регистрация системного scan-result receiver на время активности.
- Запрос Wi-Fi scan через `WifiManager.startScan()`.
- Отображение SSID, BSSID, RSSI, частоты и примерного диапазона.
- Сохранение measurement timestamp отдельно от receive timestamp.
- Явная индикация, обновились ли результаты; старые результаты не маркируются свежими.

## Известные ограничения

- Android ограничивает частоту сканирований; `startScan()` устарел и может быть отклонён системой.
- Для получения результатов может потребоваться включённая системная геолокация.
- Список пока отображает результаты текущего системного Wi-Fi scan, но не записывает сессии.
- Позиционирование, камера, IMU, траектория на экране, SQLite и Rust/Kotlin FFI ещё не подключены.
- Android сборка в текущей среде не запускалась; перед использованием требуется локальная Gradle-сборка.
